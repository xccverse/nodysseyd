@file:OptIn(ExperimentalForeignApi::class)

package io.github.nodyssey.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.nodyssey.core.NodeSeekSite
import io.github.plaza.core.net.UserAgent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * The Apple half of h1's 人机验证 block — the same page, hosted by `WKWebView`.
 *
 * The bridge is where the two platforms genuinely differ: Android hands JavaScript a Java object,
 * WebKit gives it a message handler on `window.webkit.messageHandlers`. A [WKUserScript] injected at
 * document start defines `__nsBridge` over that, so [turnstileDocument] does not have to know which
 * host it landed in.
 *
 * Two things Apple cannot copy from Android, and both are about where the markup is allowed to live:
 *
 * 1. Android's `loadDataWithBaseURL` keeps the document's origin; `loadHTMLString` does not. A sitekey
 *    is bound to the hostnames it may be rendered on, so a widget whose document has no origin is
 *    refused before the user ever sees a checkbox — which is what left this platform with nothing but
 *    「改用网页登录」. The view is therefore navigated to the site's own https origin, and the markup
 *    is written into that page once it finishes: `document.write` keeps the creating origin.
 * 2. That page brings its Content-Security-Policy along, and the site's policy allows only
 *    nonce-carrying inline scripts. The markup's own `<script>` carries no nonce, so it never runs and
 *    Cloudflare's loader finds nothing to call under `onload`. The injection hands the written tags
 *    the page's nonce and defines the two entry points itself, so the widget comes up either way.
 *
 * Contained the same way the Android actual is: only the site's own host may load, every other
 * main-frame navigation is cancelled, and the page is never handed a URL it can follow out.
 */
@Composable
actual fun TurnstileWidget(
    sitekey: String,
    darkTheme: Boolean,
    userAgent: UserAgent,
    resetSignal: Int,
    onToken: (String) -> Unit,
    onExpired: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier,
) {
    val currentToken by rememberUpdatedState(onToken)
    val currentExpired by rememberUpdatedState(onExpired)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val document = remember(sitekey, darkTheme) { turnstileDocument(sitekey, darkTheme) }

    val host =
        remember(document, sitekey, darkTheme) {
            TurnstileHost(
                document = document,
                sitekey = sitekey,
                darkTheme = darkTheme,
                onToken = { currentToken(it) },
                onExpired = { currentExpired() },
                onUnavailable = { currentUnavailable() },
            )
        }
    val webView =
        remember(document, userAgent) {
            newTurnstileWebView(userAgent, host).also { view ->
                // A real navigation to the site's own origin, not `loadHTMLString`: see the KDoc.
                NSURL.URLWithString(NodeSeekSite.BASE_URL + "/")?.let { url ->
                    view.loadRequest(NSURLRequest(uRL = url))
                }
            }
        }

    // Skips the first composition: the widget fetches a token on its own when it renders, and
    // resetting a box the user has not ticked yet would be a wasted round trip.
    LaunchedEffect(resetSignal) {
        if (resetSignal != 0) webView.evaluateJavaScript("nsTurnstileReset()", null)
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
        onRelease = { view ->
            view.stopLoading()
            view.configuration.userContentController.removeScriptMessageHandlerForName(TURNSTILE_BRIDGE)
        },
    )
}

private fun newTurnstileWebView(userAgent: UserAgent, host: TurnstileHost): WKWebView {
    val controller = WKUserContentController()
    controller.addScriptMessageHandler(host, TURNSTILE_BRIDGE)
    controller.addUserScript(
        WKUserScript(
            source = BRIDGE_SHIM,
            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            // The shim has to exist in the frame the widget's own script runs in, and only there.
            forMainFrameOnly = true,
        ),
    )
    val configuration = WKWebViewConfiguration()
    configuration.userContentController = controller
    configuration.websiteDataStore = WKWebsiteDataStore.defaultDataStore()
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
    val webView = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
    if (!userAgent.isWebViewDefault) webView.customUserAgent = userAgent.value
    // The strip sits on the form's own surface; an opaque white box would be a hole in it.
    webView.opaque = false
    webView.backgroundColor = UIColor.clearColor
    webView.scrollView.scrollEnabled = false
    webView.navigationDelegate = host
    return webView
}

/**
 * Message handler and navigation policy in one object.
 *
 * `NSObject` and the two protocols rather than lambdas, for the reason the sign-in screen's own
 * delegate gives: an Objective-C delegate is an object, and both of these are set on the same view.
 */
private class TurnstileHost(
    private val document: String,
    private val sitekey: String,
    private val darkTheme: Boolean,
    private val onToken: (String) -> Unit,
    private val onExpired: () -> Unit,
    private val onUnavailable: () -> Unit,
) : NSObject(),
    WKScriptMessageHandlerProtocol,
    WKNavigationDelegateProtocol {

    private var hasWrittenDocument = false

    /** The only host this view may load from — the site the sitekey is registered against. */
    private val siteHost: String? = NSURL.URLWithString(NodeSeekSite.BASE_URL)?.host

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? Map<*, *> ?: return onUnavailable()
        dispatchTurnstileMessage(
            kind = body["kind"] as? String ?: "",
            value = body["value"] as? String ?: "",
            onToken = onToken,
            onExpired = onExpired,
            onUnavailable = onUnavailable,
        )
    }

    /**
     * The site's own page has loaded — now put the widget's markup in it.
     *
     * Written once: the markup carries no navigations of its own, and a second write would restart
     * the challenge the user may already have passed. Only the site's own host qualifies — an
     * interstitial served from anywhere else must not have the widget written into it, or the
     * sitekey would be rendered on a hostname it is not registered for.
     */
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (hasWrittenDocument) return
        if (webView.URL?.host != siteHost) return
        hasWrittenDocument = true
        webView.evaluateJavaScript(widgetInjectionScript(document, sitekey, darkTheme), null)
    }

    /**
     * The site's own host, and nothing else.
     *
     * A host test rather than the first-load count this used to be: the navigation now goes to a real
     * page, which may redirect within the site, and a count would have cancelled the redirect. The
     * widget's own iframe is a subframe load and never reaches here.
     */
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL
        val staysOnSite = target != null && target.host != null && target.host == siteHost
        decisionHandler(
            if (staysOnSite) {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }
}

/**
 * Puts the widget into the page the site's own origin already served, then re-establishes everything
 * that page needs to come up.
 *
 * In order:
 * 1. Read the served page's CSP nonce off one of its own `<script>` tags and hand it to the tags in
 *    the markup we are about to write: the site's policy allows only nonce-carrying inline scripts,
 *    so without it the widget's own script is dropped on the floor.
 * 2. `document.open()` / `write` / `close()`, which replaces the document while keeping the origin it
 *    was written from.
 * 3. Redefine the bridge — the write threw away the document the injected shim ran in.
 * 4. Define `nsTurnstileReady` and `nsTurnstileReset` here rather than trusting the markup's own copy,
 *    which may or may not have been allowed through. Cloudflare's loader calls the former by name
 *    (`onload=…`), so it has to exist under exactly that name either way.
 */
private fun widgetInjectionScript(document: String, sitekey: String, darkTheme: Boolean): String {
    val theme = if (darkTheme) "dark" else "light"
    return buildString {
        append("(function(){")
        append("var n='';var s=document.getElementsByTagName('script');")
        append("for(var i=0;i<s.length;i++){if(s[i].nonce){n=s[i].nonce;break;}}")
        append("var h=").append(javaScriptStringLiteral(document)).append(";")
        append("if(n){h=h.split('<script').join('<script nonce=\"'+n+'\"');}")
        append("document.open();document.write(h);document.close();")
        append("})();")
        append('\n').append(BRIDGE_SHIM).append('\n')
        append("window.nsTurnstileReset=function(){try{turnstile.reset();}catch(e){}};")
        append("window.nsTurnstileReady=function(){try{turnstile.render('#w',{")
        append("sitekey:").append(javaScriptStringLiteral(sitekey)).append(',')
        append("theme:").append(javaScriptStringLiteral(theme)).append(',')
        append("callback:function(t){").append(TURNSTILE_BRIDGE).append(".post('token',t);},")
        append("'expired-callback':function(){").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'timeout-callback':function(){").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'error-callback':function(e){").append(TURNSTILE_BRIDGE).append(".post('error',String(e||''));}")
        append("});}catch(e){").append(TURNSTILE_BRIDGE).append(".post('error',String(e));}};")
    }
}

/** The markup as one JavaScript string literal — escaped, since `evaluateJavaScript` takes source. */
private fun javaScriptStringLiteral(text: String): String {
    val out = StringBuilder(text.length + 32)
    out.append('"')
    for (c in text) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\u2028' -> out.append("\\u2028")
            '\u2029' -> out.append("\\u2029")
            else -> out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

/** Gives the page the same `__nsBridge.post(kind, value)` the Android host exposes directly. */
private val BRIDGE_SHIM =
    """
    window.$TURNSTILE_BRIDGE = {
      post: function (kind, value) {
        window.webkit.messageHandlers.$TURNSTILE_BRIDGE.postMessage({ kind: kind, value: value });
      }
    };
    """.trimIndent()
