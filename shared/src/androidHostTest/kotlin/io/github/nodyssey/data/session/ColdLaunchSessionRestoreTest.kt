package io.github.nodyssey.data.session

import io.github.nodyssey.core.NodeSeekSite
import io.github.nodyssey.data.NoOpPostRepository
import io.github.nodyssey.data.OfflineLibrary
import io.github.nodyssey.data.OfflineSettings
import io.github.nodyssey.data.OfflineState
import io.github.nodyssey.data.OfflineUsage
import io.github.nodyssey.data.ProfileRepository
import io.github.nodyssey.data.UserProfile
import io.github.nodyssey.data.composer.CommentComposerRepository
import io.github.nodyssey.data.composer.CommentDraft
import io.github.nodyssey.data.composer.CommentSubmission
import io.github.nodyssey.data.composer.PostComposerRepository
import io.github.nodyssey.data.composer.PostDraft
import io.github.nodyssey.data.composer.PostSubmission
import io.github.nodyssey.data.doubles.session.FakeAccountDao
import io.github.nodyssey.data.local.AccountEntity
import io.github.nodyssey.data.local.AccountSessionEntity
import io.github.nodyssey.data.local.ActiveAccountEntity
import io.github.nodyssey.data.security.SecretCipher
import io.github.nodyssey.data.settings.SettingsRepository
import io.github.nodyssey.data.testPreferenceStore
import io.github.plaza.core.AppClock
import io.github.plaza.core.net.SessionCookies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Apple half of the account story: a launch whose cookie jar is empty.
 *
 * That is not a hypothetical on iOS. The session lives in the two system jars
 * (`NSHTTPCookieStorage` and WebKit's), and both lose their contents when the process is killed —
 * so "swiped away from the app switcher, next launch shows the sign-in screen" is what users report.
 * Android never shows it because its `CookieManager` persists on its own.
 *
 * The snapshot in Room is the durable copy, and [AccountRepository.restoreSessionOnLaunch] is what
 * puts it back. This pins the behaviour: an empty jar plus a stored snapshot has to end up signed
 * in, without going near the sign-in endpoint (the fake's password is empty, so a silent re-login
 * would fail — the assertion can only pass by refilling from the snapshot).
 */
class ColdLaunchSessionRestoreTest {

    @Test
    fun `a jar emptied by a kill is refilled from the stored snapshot`() = runTest {
        val cookies = FakeSessionCookieStore()
        val sessionCookies = SessionCookies(NodeSeekSite.CONFIG, cookies)
        val session = SessionRepository(sessionCookies)
        session.sync()
        assertFalse("起点：系统罐子是空的 —— 就是杀进程后的状态", session.state.value.isSignedIn)

        val dao = FakeAccountDao()
        dao.upsertAccount(
            AccountEntity(
                id = "42",
                uid = 42L,
                username = "tester",
                displayName = "tester",
                avatarUrl = "",
                addedAtMillis = 1L,
                lastActiveAtMillis = 1L,
            ),
        )
        dao.upsertSession(
            AccountSessionEntity(
                accountId = "42",
                cookieSnapshotJson = """[{"name":"session","value":"abc123"}]""",
                // 故意留空：静默重登必然失败，于是这条测试只能靠"把快照装回去"通过。
                encryptedPassword = "",
                updatedAtMillis = 1L,
            ),
        )
        dao.setActiveAccount(ActiveAccountEntity(accountId = "42"))

        val accounts =
            AccountRepository(
                dao = dao,
                cookies = sessionCookies,
                session = session,
                signIn = StubSignInRepository(),
                profiles = StubProfileRepository(),
                cipher = StubSecretCipher(),
                clock = AppClock.System,
                posts = NoOpPostRepository(),
                offline = StubOfflineLibrary(),
                postDrafts = StubPostComposer(),
                commentDrafts = StubCommentComposer(),
                settings = SettingsRepository(testPreferenceStore(backgroundScope, name = "cold-launch")),
            )

        assertTrue("冷启动恢复应当成功", accounts.restoreSessionOnLaunch())
        assertTrue("装回快照之后就是登录态", session.state.value.isSignedIn)
        assertEquals("session=abc123", cookies.cookieHeader(NodeSeekSite.BASE_URL))
    }

    @Test
    fun `nothing to restore leaves the caller signed out`() = runTest {
        val cookies = FakeSessionCookieStore()
        val sessionCookies = SessionCookies(NodeSeekSite.CONFIG, cookies)
        val session = SessionRepository(sessionCookies)
        session.sync()

        // 没有 active 行 —— 全新安装。恢复必须是"什么都不做"，而不是随手登一个账号。
        val accounts =
            AccountRepository(
                dao = FakeAccountDao(),
                cookies = sessionCookies,
                session = session,
                signIn = StubSignInRepository(),
                profiles = StubProfileRepository(),
                cipher = StubSecretCipher(),
                clock = AppClock.System,
                posts = NoOpPostRepository(),
                offline = StubOfflineLibrary(),
                postDrafts = StubPostComposer(),
                commentDrafts = StubCommentComposer(),
                settings = SettingsRepository(testPreferenceStore(backgroundScope, name = "cold-launch-empty")),
            )

        assertFalse(accounts.restoreSessionOnLaunch())
        assertFalse(session.state.value.isSignedIn)
    }
}

private class StubSignInRepository : SignInRepository {
    override suspend fun signIn(credentials: SignInCredentials): SignInOutcome = SignInOutcome.Signed

    override suspend fun verifyTwoFactor(challenge: TwoFactorChallenge, code: String): SignInOutcome =
        SignInOutcome.Signed
}

private class StubProfileRepository : ProfileRepository {
    private val profile =
        UserProfile(
            uid = 42L,
            name = "tester",
            avatarUrl = "${NodeSeekSite.BASE_URL}/avatar/42.png",
        )

    override suspend fun clearCachedProfile() = Unit

    override suspend fun profile(refresh: Boolean): UserProfile = profile

    override suspend fun profile(uid: Long): UserProfile = profile.copy(uid = uid)
}

private class StubSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String? = null

    override fun decrypt(stored: String): String = ""
}

private class StubOfflineLibrary : OfflineLibrary {
    override val isAvailable: Boolean = true
    override val states: Flow<Map<Long, OfflineState>> = flowOf(emptyMap())
    override val usage: Flow<OfflineUsage> = flowOf(OfflineUsage())
    override val settings: Flow<OfflineSettings> = MutableStateFlow(OfflineSettings())

    override suspend fun download(postIds: Collection<Long>) = Unit

    override suspend fun noteReplyCounts(counts: Map<Long, Int>) = Unit

    override suspend fun estimateBytes(postIds: Collection<Long>): Long? = null

    override suspend fun cancel(postId: Long) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun updateSettings(settings: OfflineSettings) = Unit
}

private class StubPostComposer : PostComposerRepository {
    override val draft: Flow<PostDraft?> = flowOf(null)

    override suspend fun saveDraft(draft: PostDraft) = Unit

    override suspend fun deleteDraft() = Unit

    override suspend fun publish(submission: PostSubmission): Long? = null
}

private class StubCommentComposer : CommentComposerRepository {
    override fun draft(postId: Long): Flow<CommentDraft?> = flowOf(null)

    override suspend fun saveDraft(postId: Long, draft: CommentDraft) = Unit

    override suspend fun deleteDraft(postId: Long) = Unit

    override suspend fun deleteAllDrafts() = Unit

    override suspend fun publish(submission: CommentSubmission): Int? = null
}
