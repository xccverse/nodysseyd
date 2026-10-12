package io.github.nodyssey.data.session

import io.github.nodyssey.core.NodeSeekSite
import io.github.nodyssey.data.NoOpPostRepository
import io.github.nodyssey.data.OfflineLibrary
import io.github.nodyssey.data.ProfileRepository
import io.github.nodyssey.data.UserProfile
import io.github.nodyssey.data.composer.CommentComposerRepository
import io.github.nodyssey.data.composer.PostComposerRepository
import io.github.nodyssey.data.doubles.session.FakeAccountDao
import io.github.nodyssey.data.security.SecretCipher
import io.github.nodyssey.data.settings.SettingsRepository
import io.github.nodyssey.data.testPreferenceStore
import io.github.plaza.core.AppClock
import io.github.plaza.core.net.SessionCookies
import io.github.plaza.core.net.SiteError
import io.github.plaza.core.net.SiteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号核心逻辑的测试。
 *
 * 每个测试都是一个用户故事：
 * - 登录收编：首次登录建账号行，切换回来不用重新登录
 * - 切换：A→B→A，cookie 快照来回倒
 * - 过期：快照死了，有密码就静默重登，没密码就告诉 UI
 * - 退出：清场但账号留着
 * - 删除：连快照一起删
 */
class AccountRepositoryTest {
    private val dao = FakeAccountDao()
    private val cookieStore = FakeSessionCookieStore()
    private val cookies = SessionCookies(NodeSeekSite.CONFIG, cookieStore)
    private val session = SessionRepository(cookies)
    private val signIn = FakeSignInRepository()
    private val profiles = FakeProfileRepository()
    private val cipher = FakeSecretCipher()
    private val clock = FakeClock()

    private fun repository(scope: CoroutineScope): AccountRepository =
        AccountRepository(
            dao = dao,
            cookies = cookies,
            session = session,
            signIn = signIn,
            profiles = profiles,
            cipher = cipher,
            clock = clock,
            posts = NoOpPostRepository(),
            offline = NoOpOfflineLibrary(),
            postDrafts = NoOpPostComposer(),
            commentDrafts = NoOpCommentComposer(),
            settings = SettingsRepository(testPreferenceStore(scope, name = "account-test")),
        )

    private fun signInAs(uid: Long, name: String, cookieValue: String = "session-$uid") {
        cookieStore.setCookie(NodeSeekSite.BASE_URL, "session=$cookieValue")
        session.sync()
        profiles.current = UserProfile(uid = uid, name = name, avatarUrl = "")
    }

    @Test
    fun `adoptCurrentSession creates account and marks it active`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")

        repo.adoptCurrentSession(username = "alice", password = "secret")

        val accounts = repo.accounts.first()
        assertEquals(1, accounts.size)
        assertEquals("uid-1001", accounts[0].id)
        assertEquals(1001L, accounts[0].uid)
        assertEquals("alice", accounts[0].username)
        assertTrue(accounts[0].isActive)
        assertTrue(accounts[0].hasStoredPassword)
        assertEquals("uid-1001", repo.activeAccountId.first())
    }

    @Test
    fun `adoptCurrentSession with empty password stores no password`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")

        repo.adoptCurrentSession(username = "alice", password = "")

        val accounts = repo.accounts.first()
        assertEquals(1, accounts.size)
        assertFalse(accounts[0].hasStoredPassword)
        assertEquals("", repo.storedPassword("uid-1001"))
    }

    @Test
    fun `switchTo moves session between accounts`() = runTest {
        val repo = repository(backgroundScope)
        // A 登录
        signInAs(uid = 1001, name = "alice", cookieValue = "session-alice")
        repo.adoptCurrentSession(username = "alice", password = "pw-a")
        // B 登录（adopt 会收编 B 并切过去）
        signInAs(uid = 1002, name = "bob", cookieValue = "session-bob")
        repo.adoptCurrentSession(username = "bob", password = "pw-b")

        // 切回 A
        val result = repo.switchTo("uid-1001")

        assertEquals(SwitchResult.Switched, result)
        assertEquals("uid-1001", repo.activeAccountId.first())
        // 罐子里现在是 A 的 cookie
        assertTrue(session.peek().isSignedIn)
        val accounts = repo.accounts.first()
        assertEquals(2, accounts.size)
        assertTrue(accounts.first { it.id == "uid-1001" }.isActive)
        assertFalse(accounts.first { it.id == "uid-1002" }.isActive)
    }

    @Test
    fun `switchTo same account returns AlreadyActive`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "pw")

        val result = repo.switchTo("uid-1001")

        assertEquals(SwitchResult.AlreadyActive, result)
    }

    @Test
    fun `switchTo unknown account returns NoSuchAccount`() = runTest {
        val repo = repository(backgroundScope)

        val result = repo.switchTo("uid-9999")

        assertEquals(SwitchResult.NoSuchAccount, result)
    }

    @Test
    fun `switchTo expired snapshot with stored password does silent re-login`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice", cookieValue = "session-alice")
        repo.adoptCurrentSession(username = "alice", password = "pw-a")
        signInAs(uid = 1002, name = "bob", cookieValue = "session-bob")
        repo.adoptCurrentSession(username = "bob", password = "pw-b")

        // 把 A 的快照搞坏（模拟服务器端过期）：下次切过去时验活失败
        profiles.failWithLoginRequired = true
        signIn.signInHandler = { credentials ->
            // 静默重登：用记住的密码登录成功
            assertEquals("alice", credentials.account)
            assertEquals("pw-a", credentials.password)
            cookieStore.setCookie(NodeSeekSite.BASE_URL, "session=alice-new")
            session.sync()
            profiles.failWithLoginRequired = false
            profiles.current = UserProfile(uid = 1001, name = "alice", avatarUrl = "")
            SignInOutcome.Signed
        }

        val result = repo.switchTo("uid-1001")

        assertEquals(SwitchResult.Switched, result)
        assertTrue(session.peek().isSignedIn)
    }

    @Test
    fun `switchTo expired snapshot without password returns SessionExpired`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice", cookieValue = "session-alice")
        repo.adoptCurrentSession(username = "alice", password = "") // 没记住密码
        signInAs(uid = 1002, name = "bob", cookieValue = "session-bob")
        repo.adoptCurrentSession(username = "bob", password = "pw-b")

        profiles.failWithLoginRequired = true
        // 删掉 A 的快照 cookie，模拟完全过期
        cookieStore.setCookie(NodeSeekSite.BASE_URL, "session=")

        val result = repo.switchTo("uid-1001")

        assertTrue(result is SwitchResult.SessionExpired)
        assertEquals("uid-1001", (result as SwitchResult.SessionExpired).accountId)
    }

    @Test
    fun `signOutCurrent clears session but keeps account`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "pw")

        repo.signOutCurrent()

        assertFalse(session.peek().isSignedIn)
        assertNull(repo.activeAccountId.first())
        // 账号还在列表里
        val accounts = repo.accounts.first()
        assertEquals(1, accounts.size)
        assertEquals("uid-1001", accounts[0].id)
    }

    @Test
    fun `removeAccount deletes account and session`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "pw")
        signInAs(uid = 1002, name = "bob")
        repo.adoptCurrentSession(username = "bob", password = "pw")

        repo.removeAccount("uid-1001")

        val accounts = repo.accounts.first()
        assertEquals(1, accounts.size)
        assertEquals("uid-1002", accounts[0].id)
        // 当前账号是 bob，不受影响
        assertEquals("uid-1002", repo.activeAccountId.first())
    }

    @Test
    fun `removeAccount of active account signs out`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "pw")

        repo.removeAccount("uid-1001")

        assertFalse(session.peek().isSignedIn)
        assertNull(repo.activeAccountId.first())
        assertTrue(repo.accounts.first().isEmpty())
    }

    @Test
    fun `migrateIfNeeded adopts existing session as first account`() = runTest {
        val repo = repository(backgroundScope)
        // 老用户：有登录态，但没有账号行
        signInAs(uid = 1001, name = "alice")

        repo.migrateIfNeeded()

        val accounts = repo.accounts.first()
        assertEquals(1, accounts.size)
        assertEquals("uid-1001", accounts[0].id)
        // 迁移不记密码
        assertFalse(accounts[0].hasStoredPassword)
    }

    @Test
    fun `migrateIfNeeded does nothing when already migrated`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "pw")

        repo.migrateIfNeeded()

        assertEquals(1, repo.accounts.first().size)
    }

    @Test
    fun `checkpointActiveAccount saves snapshot without changing active`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice", cookieValue = "session-alice-v1")
        repo.adoptCurrentSession(username = "alice", password = "pw")
        // 会话更新了（比如网页端刷新了 cookie）
        cookieStore.setCookie(NodeSeekSite.BASE_URL, "session=alice-v2")
        session.sync()

        repo.checkpointActiveAccount()

        assertEquals("uid-1001", repo.activeAccountId.first())
        assertTrue(session.peek().isSignedIn)
    }

    @Test
    fun `storedPassword decrypts remembered password`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice", password = "my-secret")

        assertEquals("my-secret", repo.storedPassword("uid-1001"))
    }

    @Test
    fun `accountUsername returns username`() = runTest {
        val repo = repository(backgroundScope)
        signInAs(uid = 1001, name = "alice")
        repo.adoptCurrentSession(username = "alice_login", password = "pw")

        assertEquals("alice_login", repo.accountUsername("uid-1001"))
        assertNull(repo.accountUsername("uid-9999"))
    }
}

// --- Test doubles ---

private class FakeSignInRepository : SignInRepository {
    var signInHandler: suspend (SignInCredentials) -> SignInOutcome = { SignInOutcome.Signed }

    override suspend fun signIn(credentials: SignInCredentials): SignInOutcome = signInHandler(credentials)

    override suspend fun verifyTwoFactor(challenge: TwoFactorChallenge, code: String): SignInOutcome =
        SignInOutcome.Signed
}

private class FakeProfileRepository : ProfileRepository {
    var current: UserProfile = UserProfile(uid = 0, name = "", avatarUrl = "")
    var failWithLoginRequired = false

    override suspend fun profile(refresh: Boolean): UserProfile {
        if (failWithLoginRequired) throw SiteException(SiteError.LoginRequired, detail = "login required")
        return current
    }

    override suspend fun profile(uid: Long): UserProfile = current

    override suspend fun clearCachedProfile() {}
}

private class FakeSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String? = if (plaintext.isEmpty()) "" else "enc:$plaintext"

    override fun decrypt(stored: String): String = stored.removePrefix("enc:")
}

private class FakeClock : AppClock {
    private var now = 1_000_000L

    override fun nowMillis(): Long = now.also { now += 1_000 }
}

private class NoOpOfflineLibrary : OfflineLibrary {
    override val isAvailable: Boolean = true
    override val states: Flow<Map<Long, io.github.nodyssey.data.OfflineState>> = flowOf(emptyMap())
    override val usage: Flow<io.github.nodyssey.data.OfflineUsage> = flowOf(io.github.nodyssey.data.OfflineUsage())
    override val settings: Flow<io.github.nodyssey.data.OfflineSettings> =
        flowOf(io.github.nodyssey.data.OfflineSettings())

    override suspend fun download(postIds: Collection<Long>) {}

    override suspend fun noteReplyCounts(counts: Map<Long, Int>) {}

    override suspend fun estimateBytes(postIds: Collection<Long>): Long? = null

    override suspend fun cancel(postId: Long) {}

    override suspend fun clearAll() {}

    override suspend fun updateSettings(settings: io.github.nodyssey.data.OfflineSettings) {}
}

private class NoOpPostComposer : PostComposerRepository {
    override val draft: Flow<io.github.nodyssey.data.composer.PostDraft?> = flowOf(null)

    override suspend fun saveDraft(draft: io.github.nodyssey.data.composer.PostDraft) {}

    override suspend fun deleteDraft() {}

    override suspend fun publish(submission: io.github.nodyssey.data.composer.PostSubmission): Long? = null
}

private class NoOpCommentComposer : CommentComposerRepository {
    override fun draft(postId: Long): Flow<io.github.nodyssey.data.composer.CommentDraft?> = flowOf(null)

    override suspend fun saveDraft(postId: Long, draft: io.github.nodyssey.data.composer.CommentDraft) {}

    override suspend fun deleteDraft(postId: Long) {}

    override suspend fun deleteAllDrafts() {}

    override suspend fun publish(submission: io.github.nodyssey.data.composer.CommentSubmission): Int? = null
}
