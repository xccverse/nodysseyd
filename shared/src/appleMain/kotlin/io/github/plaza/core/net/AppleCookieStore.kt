package io.github.plaza.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * screen is open*; this class is what keeps them in step the rest of the time, which is what the
 * account switcher needs. A swap that wrote only the `NSURLSession` jar left the previous account's
 * cookies sitting in WebKit, and the next `seed()` copied that stale set straight back over the new
 * account's — a switch that undid itself.
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

    /**
     * Writes to both jars, so that the app's own requests and the sign-in browser are never holding
     * two different sessions.
     *
     * WebKit's half is fire-and-forget: its API answers on a callback and this method cannot wait for
     * one. The writes it receives are ordered, so they land in the order they were handed over — which
     * is what makes them safe after [removeAll], whose WebKit half *is* awaited.
     */
    override fun setCookie(url: String, cookie: String) {
        val target = NSURL.URLWithString(url) ?: return
        val parsed =
            NSHTTPCookie.cookiesWithResponseHeaderFields(
                mapOf<Any?, Any?>("Set-Cookie" to cookie),
                forURL = target,
            )
        storage.setCookies(parsed, forURL = target, mainDocumentURL = null)
        parsed.filterIsInstance<NSHTTPCookie>().forEach { typed ->
            webKitCookies.setCookie(typed) { }
        }
    }

    /**
     * Clears both jars, and returns only once WebKit is empty too.
     *
     * That wait is the whole point of it: a swap clears and then immediately writes, and a WebKit half
     * still holding the previous account would leave the sign-in browser (and the next `seed()`) on
     * the account just left behind.
     */
    override suspend fun removeAll() {
        storage.cookies.orEmpty().filterIsInstance<NSHTTPCookie>().forEach { storage.deleteCookie(it) }
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

    // `NSHTTPCookieStorage` writes through on its own; there is no batched state to push. Android's
    // `CookieManager` is the one that needs telling, which is why the method exists at all.
    override fun flush() = Unit
}
