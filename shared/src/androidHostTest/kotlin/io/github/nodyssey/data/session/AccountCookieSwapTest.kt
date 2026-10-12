package io.github.nodyssey.data.session

import io.github.nodyssey.core.NodeSeekSite
import io.github.plaza.core.net.SessionCookieStore
import io.github.plaza.core.net.SessionCookies
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A swap has to clear the jar before it writes the incoming session.
 *
 * The order is not cosmetic. On Apple the clear is also the only thing that empties the jar the
 * sign-in WebView writes to, and a write landing before it would be undone by the previous account's
 * cookies still sitting there — see `AppleCookieStore`. These tests pin the contract that platform
 * half leans on, and the state a jar is left in afterwards.
 */
class AccountCookieSwapTest {
    private val recording = RecordingSessionCookieStore()
    private val cookies = SessionCookies(NodeSeekSite.CONFIG, recording)

    private fun incoming(vararg pairs: Pair<String, String>) = pairs.toList()

    @Test
    fun `clears the jar before writing the incoming session`() = runTest {
        recording.setCookie(NodeSeekSite.BASE_URL, "session=outgoing")

        cookies.importSessionCookies(incoming("session" to "incoming"))

        assertEquals("clear", recording.calls.first())
        assertEquals("set:session", recording.calls[1])
        assertEquals("flush", recording.calls.last())
    }

    @Test
    fun `leaves nothing of the account it replaced`() = runTest {
        val store = FakeSessionCookieStore()
        val jar = SessionCookies(NodeSeekSite.CONFIG, store)
        store.setCookie(NodeSeekSite.BASE_URL, "cf_clearance=stale")
        store.setCookie(NodeSeekSite.BASE_URL, "session=outgoing")
        assertTrue("the outgoing account really was signed in", jar.snapshot().isSignedIn)

        jar.importSessionCookies(incoming("session" to "incoming"))

        val swapped = jar.snapshot()
        assertTrue(swapped.isSignedIn)
        assertFalse("the Clearance the previous account earned is gone with it", swapped.hasClearance)
    }
}

/** Records the order of the calls, which is the half of the contract a plain map cannot show. */
private class RecordingSessionCookieStore : SessionCookieStore {
    val calls = mutableListOf<String>()
    private val cookies = LinkedHashMap<String, MutableMap<String, String>>()

    override fun cookieHeader(url: String): String? =
        cookies[url]?.entries?.joinToString("; ") { (name, value) -> "$name=$value" }?.ifEmpty { null }

    override fun setCookie(url: String, cookie: String) {
        val pair = cookie.substringBefore(';')
        calls += "set:${pair.substringBefore('=').trim()}"
        cookies.getOrPut(url) { LinkedHashMap() }[pair.substringBefore('=').trim()] =
            pair.substringAfter('=', "").trim()
    }

    override suspend fun removeAll() {
        calls += "clear"
        cookies.clear()
    }

    override fun flush() {
        calls += "flush"
    }
}
