package io.github.plaza.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import platform.WebKit.WKHTTPCookieStore
import kotlin.coroutines.resume

/**
 * [SessionCookieStore] over the two jars an Apple build actually has: the `NSHTTPCookieStorage` that
 * `NSURLSession` reads, and the `WKHTTPCookieStore` a `WKWebView` — the sign-in screen — writes to.
 *
 * On Android a single store — `CookieManager` — is both what the sign-in WebView writes to and what
 * OkHttp reads, so `WebViewCookieJar` is pure translation and nothing has to be copied. WebKit has no
 * such store: the two jars are different objects, WebKit's API is asynchronous besides, and nothing
 * syncs them on its own. [WebKitCookieBridge] is what carries one to the other *while the sign-in
 * screen is open*; this class is what keeps them from disagreeing the rest of the time, which is what
 * the account switcher needs. A swap that wrote only the `NSURLSession` jar left the previous
 * account's cookies sitting in WebKit, and the next `seed()` copied that stale set straight back over
 * the new account's — a switch that undid itself.
 *
 * **WebKit is touched in exactly one place, and never on a caller's thread.** The reads and writes
 * that carry the *current* session into WebKit are the bridge's job and happen while its screen is
 * up; all this class does is empty that jar when the session ends — see [removeAll]. Writing to WebKit
 * from [setCookie] was tried and reverted: that method is called from the transport's own thread, and
 * `WKHTTPCookieStore` answers on the main queue, so an off-thread write there hung the request that
 * made it — an app that span forever with every page failing behind it.
 *
 * Both storages are required arguments rather than defaulted ones so that a caller has to say which
 * jar it means. It is also why the interface's synchronous [cookieHeader] is honest here and would
 * not be if this read WebKit directly: `WKHTTPCookieStore.getAllCookies` answers on a callback, so
 * the bridge has to keep a mirror this can read rather than reaching across at call time.
 */
class AppleCookieStore(
    private val storage: NSHTTPCookieStorage,
    private val webKitCookies: WKHTTPCookieStore,
) : SessionCookieStore {

    override fun cookieHeader(url: String): String? {
        val target = NSURL.URLWithString(url) ?: return null
        val cookies = storage.cookiesForURL(target).orEmpty()
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { cookie ->
            val typed = cookie as NSHTTPCookie
            "${typed.name}=${typed.value}"
        }
    }

    override fun setCookie(url: String, cookie: String) {
        val target = NSURL.URLWithString(url) ?: return
        val parsed =
            NSHTTPCookie.cookiesWithResponseHeaderFields(
                mapOf<Any?, Any?>("Set-Cookie" to cookie),
                forURL = target,
            )
        storage.setCookies(parsed, forURL = target, mainDocumentURL = null)
    }

    /**
     * Empties the `NSURLSession` jar and then asks WebKit to empty its own.
     *
     * Both halves are needed. Clearing only the `NSURLSession` jar — what a swap used to do — left the
     * previous account's cookies in WebKit for the next `seed()` to copy back over the new account.
     * Clearing WebKit from here and nowhere else is what keeps the cost off the request path: this
     * runs when a session ends, not when one is used.
     *
     * WebKit's half is best-effort and bounded. It has to run on the main queue — `WKHTTPCookieStore`
     * answers there — and a switch, which holds the account lock while it works, must not be able to
     * wait on a queue for longer than a person will. The clear is handed to that queue before any
     * `seed()` a sign-in screen issues afterwards, and the queue runs the two in order, so a bounded
     * wait costs nothing in correctness.
     */
    override suspend fun removeAll() {
        storage.cookies.orEmpty().filterIsInstance<NSHTTPCookie>().forEach { storage.deleteCookie(it) }
        withTimeoutOrNull(CLEAN_WEB_KIT_TIMEOUT_MILLIS) {
            withContext(Dispatchers.Main) {
                val live =
                    suspendCancellableCoroutine<List<NSHTTPCookie>> { continuation ->
                        webKitCookies.getAllCookies { cookies ->
                            continuation.resume(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
                        }
                    }
                live.forEach { cookie ->
                    suspendCancellableCoroutine<Unit> { continuation ->
                        webKitCookies.deleteCookie(cookie) { continuation.resume(Unit) }
                    }
                }
            }
        }
    }

    // `NSHTTPCookieStorage` writes through on its own; there is no batched state to push. Android's
    // `CookieManager` is the one that needs telling, which is why the method exists at all.
    override fun flush() = Unit
}

/**
 * How long a sign-out or a swap will wait for WebKit to confirm it is empty.
 *
 * Long enough for a jar that answers normally, short enough that a queue that never answers costs one
 * moment rather than the screen the caller was on.
 */
private const val CLEAN_WEB_KIT_TIMEOUT_MILLIS = 2_000L
