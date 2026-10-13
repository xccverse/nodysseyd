package io.github.nodyssey.data.session

import io.github.nodyssey.data.NotificationCounts
import io.github.nodyssey.data.OfflineLibrary
import io.github.nodyssey.data.PostRepository
import io.github.nodyssey.data.ProfileRepository
import io.github.nodyssey.data.UserProfile
import io.github.nodyssey.data.composer.CommentComposerRepository
import io.github.nodyssey.data.composer.PostComposerRepository
import io.github.nodyssey.data.local.AccountDao
import io.github.nodyssey.data.local.AccountEntity
import io.github.nodyssey.data.local.AccountSessionEntity
import io.github.nodyssey.data.local.ActiveAccountEntity
import io.github.nodyssey.data.security.SecretCipher
import io.github.nodyssey.data.settings.SettingsRepository
import io.github.plaza.core.AppClock
import io.github.plaza.core.net.SessionCookies
import io.github.plaza.core.net.SiteError
import io.github.plaza.core.net.SiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 账号列表里的一行。 */
data class Account(
    val id: String,
    val uid: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val hasStoredPassword: Boolean,
    val isActive: Boolean,
    val isExpired: Boolean,
)

/** 切换账号的结果。 */
sealed interface SwitchResult {
    data object Switched : SwitchResult
    data object AlreadyActive : SwitchResult
    data object NoSuchAccount : SwitchResult
    data class SessionExpired(val accountId: String) : SwitchResult
}

/**
 * 登录屏和账号切换器对账号仓库的最小需求 —— SignInViewModel 只依赖这个，
 * 不直接碰 AccountRepository。
 */
interface AccountSessionStore {
    suspend fun adoptCurrentSession(username: String, password: String)

    suspend fun accountUsername(accountId: String): String?

    /** 解密后的密码；空字符串 = 没记住，走完整登录。 */
    suspend fun storedPassword(accountId: String): String
}

/**
 * 多账号：账号列表、当前账号、cookie 快照的存取、切换、退出。
 *
 * 模型：会话 = cookie 罐，罐子一次只能装一个账号（见 [SessionCookies]）。所以"多账号登录"
 * 的真相是每个账号一份快照 + 同一时刻只有一个快照在罐子里；切换 = 存盘当前 → 清场 →
 * 装入目标 → 发布。
 *
 * 清场沿用退出登录的 fail-closed 规则：帖子缓存、个人资料、离线库、草稿、通知水位里
 * 分不清是谁的数据，旧账号的数据新账号一个都不能看 —— 这份清单原来住在
 * [DefaultAccountSignOut]，搬到这里是因为切换和退出是同一件事（"退出"现在是"切到无账号"，
 * 账号本身保留在列表里随时可切回）。新增的、持有账号数据的 store，把清理行加在
 * [evictAccountData] 里。
 */
class AccountRepository(
    private val dao: AccountDao,
    private val cookies: SessionCookies,
    private val session: SessionRepository,
    private val signIn: SignInRepository,
    private val profiles: ProfileRepository,
    private val cipher: SecretCipher,
    private val clock: AppClock,
    private val posts: PostRepository,
    private val offline: OfflineLibrary,
    private val postDrafts: PostComposerRepository,
    private val commentDrafts: CommentComposerRepository,
    private val settings: SettingsRepository,
) : AccountSessionStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 同一轮过期里静默重登试过的会话指纹 —— 守护进程每轮都调 `trySilentReLogin`，
     * 没有这个，一轮里会拿记住的密码反复撞登录接口。
     * 成功登录（adopt）或会话指纹变化时清零。
     */
    private var silentAttemptFingerprint: Int? = null

    val accounts: Flow<List<Account>> =
        combine(
            dao.observeAccounts(),
            dao.observeSessions(),
            dao.observeActiveAccount(),
            session.state,
        ) { entities, sessions, active, sessionState ->
            val passwordById = sessions.associate { it.accountId to it.encryptedPassword.isNotEmpty() }
            entities.map { entity ->
                Account(
                    id = entity.id,
                    uid = entity.uid,
                    username = entity.username,
                    displayName = entity.displayName,
                    avatarUrl = entity.avatarUrl,
                    hasStoredPassword = passwordById[entity.id] == true,
                    isActive = entity.id == active?.accountId,
                    // 过期 = 是当前账号但会话没了；后台账号的过期在切换验活时才知道。
                    isExpired = entity.id == active?.accountId && !sessionState.isSignedIn,
                )
            }
        }

    val activeAccountId: Flow<String?> = dao.observeActiveAccount().map { it?.accountId }

    override suspend fun adoptCurrentSession(username: String, password: String) {
        val profile = profiles.profile(refresh = true)
        mutex.withLock { adopt(profile, username, password) }
    }

    override suspend fun accountUsername(accountId: String): String? = dao.account(accountId)?.username

    override suspend fun storedPassword(accountId: String): String {
        val session = dao.session(accountId) ?: return ""
        if (session.encryptedPassword.isEmpty()) return ""
        return cipher.decrypt(session.encryptedPassword).takeIf { it.isNotEmpty() } ?: ""
    }

    /** 把当前罐子里的会话存盘（如果有）。切换、退出、添加账号前调。 */
    suspend fun checkpointActiveAccount() {
        mutex.withLock {
            val activeId = dao.activeAccount()?.accountId ?: return
            if (session.peek().isSignedIn) {
                snapshotInto(activeId, SetPassword.Keep)
            }
        }
    }

    /**
     * 老用户迁移：有登录态但没有账号行 —— 把当前会话收编为第一个账号。
     * 新表是空着建的（MIGRATION_20_21），不迁的话老用户会看到空账号列表。
     */
    suspend fun migrateIfNeeded() {
        mutex.withLock {
            if (!session.peek().isSignedIn) return
            if (dao.activeAccount()?.accountId != null) return
            if (dao.accountCount() > 0) return
            val profile = catching { profiles.profile(refresh = true) } ?: return
            // 迁移不记密码 —— 老用户没被问过"记住密码"，默认不存。
            adopt(profile, profile.name, "", evict = false)
        }
    }

    /**
     * 会话守护：已登录但没有 active 行（网页登录易主、数据库被清过）。
     * 按 uid 找到账号行（没有就建），把 active 指过去。
     */
    suspend fun ensureActiveAccount() {
        mutex.withLock {
            if (!session.peek().isSignedIn) return
            if (dao.activeAccount()?.accountId != null) return
            val profile = catching { profiles.profile(refresh = true) } ?: return
            val id = accountId(profile.uid)
            if (dao.account(id) == null) {
                adopt(profile, profile.name, "", evict = false)
            } else {
                val state = session.sync()
                dao.setActiveAccount(ActiveAccountEntity(accountId = id, sessionFingerprint = state.fingerprint))
                dao.touchAccount(id, clock.nowMillis())
            }
        }
    }

    /**
     * 切到目标账号。
     *
     * 顺序：1. 存盘当前 → 2. 清场 → 3. 装入目标快照 → 4. 验活（死了就静默重登）→ 5. 发布。
     * 快照是空的（没记住、且服务器端也过期了）→ SessionExpired，UI 引导重新登录。
     */
    suspend fun switchTo(accountId: String): SwitchResult =
        mutex.withLock {
            dao.account(accountId) ?: return SwitchResult.NoSuchAccount
            val activeId = dao.activeAccount()?.accountId
            if (activeId == accountId) {
                return if (ensureActiveSession(accountId)) {
                    SwitchResult.AlreadyActive
                } else {
                    SwitchResult.SessionExpired(accountId)
                }
            }
            // 1. 存盘当前 —— 快照只在有会话时写，空快照不覆盖有效快照。
            if (activeId != null && session.peek().isSignedIn) {
                val active = dao.activeAccount()
                if (active != null && session.peek().fingerprint == active.sessionFingerprint) {
                    snapshotInto(activeId, SetPassword.Keep)
                }
                // 指纹对不上（网页登录易主）：旧账号行不动，孤儿会话在下面被目标切换自然取代。
            }
            // 2. 清场 —— 旧账号的数据新账号不能看。
            evictAccountData()
            // 3. 装入目标快照。
            val snapshot = dao.session(accountId)?.let { decodeSnapshot(it.cookieSnapshotJson) }.orEmpty()
            cookies.importSessionCookies(snapshot)
            val state = session.sync()
            dao.setActiveAccount(ActiveAccountEntity(accountId = accountId, sessionFingerprint = state.fingerprint))
            dao.touchAccount(accountId, clock.nowMillis())
            silentAttemptFingerprint = null
            // 4. 验证：快照里的 cookie 非空 ≠ 服务器还认 —— 验活，死了就地静默重登，
            // 还不行就交回给 UI（账号过期）。
            return if (ensureLiveSession(accountId)) {
                SwitchResult.Switched
            } else {
                SwitchResult.SessionExpired(accountId)
            }
        }

    /**
     * 退出登录 = 存盘当前会话 → 清场 → 清罐子 → 无 active。账号保留在列表里，随时可切回；
     * 删账号才动 [removeAccount]。
     */
    suspend fun signOutCurrent() {
        mutex.withLock {
            val activeId = dao.activeAccount()?.accountId
            if (activeId != null && session.peek().isSignedIn) {
                snapshotInto(activeId, SetPassword.Keep)
            }
            evictAccountData()
            session.signOut()
            dao.setActiveAccount(ActiveAccountEntity(accountId = null))
        }
    }

    /**
     * 删账号。删的是当前账号 → 先走一遍退出（存盘就免了，人都要删了）；
     * 删的是后台账号 → 只删行和快照。
     */
    suspend fun removeAccount(accountId: String) {
        mutex.withLock {
            val activeId = dao.activeAccount()?.accountId
            if (activeId == accountId) {
                evictAccountData()
                session.signOut()
                dao.setActiveAccount(ActiveAccountEntity(accountId = null))
            }
            dao.deleteAccountAndSession(accountId)
        }
    }

    /**
     * 静默重登：罐子空了（或快照死了）但 active 还在，且记住了密码，就拿密码调一次登录接口。
     *
     * 给 NodysseyApp 的会话守护调。一轮过期只试一次（[silentAttemptFingerprint]），
     * 2FA 的账号试了也白试（第二程要人输验证码）—— 接口会拒绝，返回 false 交回 UI。
     * 站点很可能要求 Turnstile token，没有 token 的登录大概率被拒绝，这是已知的局限。
     */
    suspend fun trySilentReLogin(): Boolean {
        val activeId = dao.activeAccount()?.accountId ?: return false
        mutex.withLock { return trySilentReLoginLocked(activeId) }
    }

    // ----------------------------------------------------------------------------------
    // 内部：调用者已持锁
    // ----------------------------------------------------------------------------------

    private suspend fun trySilentReLoginLocked(accountId: String): Boolean {
        val peek = session.peek()
        if (peek.isSignedIn) return true
        return attemptSilentLogin(accountId, peek.fingerprint)
    }

    /**
     * 切账号后的验活：快照里的 cookie 非空 ≠ 服务器还认。
     *
     * 打一次资料接口 —— 成功就是活着；`LoginRequired`（401/被踢回登录页）就是死了，
     * 有记住的密码就地静默重登一次；其他异常（断网、解析失败）验不出来，按活着处理，
     * 免得断网时把好好的账号判过期。
     */
    private suspend fun ensureLiveSession(accountId: String): Boolean {
        val peek = session.peek()
        if (!peek.isSignedIn) return trySilentReLoginLocked(accountId)
        if (isSessionAlive()) return true
        return attemptSilentLogin(accountId, peek.fingerprint)
    }

    private suspend fun isSessionAlive(): Boolean =
        try {
            profiles.profile(refresh = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            e.error !is SiteError.LoginRequired
        } catch (e: Exception) {
            true
        }

    private suspend fun attemptSilentLogin(accountId: String, fingerprint: Int): Boolean {
        if (silentAttemptFingerprint == fingerprint) return false
        silentAttemptFingerprint = fingerprint
        val account = dao.account(accountId) ?: return false
        val password = storedPassword(accountId)
        if (password.isEmpty()) return false
        val outcome =
            catching {
                signIn.signIn(
                    SignInCredentials(
                        account = account.username,
                        password = password,
                        verificationToken = null,
                    ),
                )
            } ?: return false
        // 2FA 的账号静默续不上 —— 第二程要人输验证码，老实交回 UI。
        if (outcome != SignInOutcome.Signed) return false
        if (!session.syncAwaitingSession().isSignedIn) return false
        snapshotInto(accountId, SetPassword.Keep)
        return true
    }

    /** active 账号的会话是否还活着；死了就地试一次静默重登。 */
    private suspend fun ensureActiveSession(accountId: String): Boolean {
        if (session.peek().isSignedIn) return true
        return trySilentReLoginLocked(accountId)
    }

    private suspend fun adopt(profile: UserProfile, username: String, password: String, evict: Boolean = true) {
        val id = accountId(profile.uid)
        // 同一个账号换了个新会话（网页重新登录、收编失败后的兜底）：各 store 里的数据本来就是
        // 它的，清场会白白丢掉离线下载和草稿。换了账号才清场。
        // 迁移（evict = false）同理 —— 只是把"当前罐子里这个会话"原样收编。
        val previousActiveId = dao.activeAccount()?.accountId
        if (evict && previousActiveId != id) evictAccountData()
        val now = clock.nowMillis()
        val existing = dao.account(id)
        dao.upsertAccount(
            AccountEntity(
                id = id,
                uid = profile.uid,
                username = username,
                displayName = profile.name,
                avatarUrl = profile.avatarUrl,
                addedAtMillis = existing?.addedAtMillis ?: now,
                lastActiveAtMillis = now,
            ),
        )
        snapshotInto(id, SetPassword.To(password))
        val state = session.sync()
        dao.setActiveAccount(ActiveAccountEntity(accountId = id, sessionFingerprint = state.fingerprint))
        silentAttemptFingerprint = null
    }

    private suspend fun snapshotInto(accountId: String, password: SetPassword) {
        val snapshot = cookies.exportSessionCookies()
        val encrypted =
            when (password) {
                is SetPassword.To -> if (password.value.isNotEmpty()) cipher.encrypt(password.value).orEmpty() else ""
                is SetPassword.Keep -> dao.session(accountId)?.encryptedPassword ?: ""
            }
        dao.upsertSession(
            AccountSessionEntity(
                accountId = accountId,
                cookieSnapshotJson = json.encodeToString(snapshot.map { (name, value) -> StoredCookie(name, value) }),
                encryptedPassword = encrypted,
                updatedAtMillis = clock.nowMillis(),
            ),
        )
    }

    private fun decodeSnapshot(jsonString: String): List<Pair<String, String>> =
        runCatching { json.decodeFromString<List<StoredCookie>>(jsonString) }
            .getOrNull()
            .orEmpty()
            .map { it.name to it.value }

    /**
     * 清场：帖子缓存、个人资料、离线库、两份草稿、通知水位。
     *
     * 原来住在 [DefaultAccountSignOut] 的退出清单 —— 切换和退出是同一件事，
     * 旧账号的数据新账号一个都不能看。
     */
    private suspend fun evictAccountData() {
        posts.clearSessionData()
        profiles.clearCachedProfile()
        offline.clearAll()
        postDrafts.deleteDraft()
        commentDrafts.deleteAllDrafts()
        settings.setNotificationSeenCounts(NotificationCounts())
    }

    private fun accountId(uid: Long): String = "uid-$uid"

    private suspend fun <T> catching(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    @Serializable
    private data class StoredCookie(val name: String, val value: String)

    private sealed interface SetPassword {
        data class To(val value: String) : SetPassword

        data object Keep : SetPassword
    }
}
