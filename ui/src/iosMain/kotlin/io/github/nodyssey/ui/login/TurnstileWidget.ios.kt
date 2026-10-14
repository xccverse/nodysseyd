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
 * document start defines `__nsBridge` over that, and it has to reach every frame — the widget ends up
 * in a child frame here, not in the top one.
 *
 * Android's single call — `loadDataWithBaseURL` — does not translate, and every step of the
 * translation is a way to lose the widget. In the order they were found on a device:
 *
 * 1. `loadHTMLString` documents have no origin, and a sitekey is bound to the hostnames it may be
 *    rendered on: such a widget is refused before the user sees a checkbox. So the view is navigated
 *    to the site's own https origin instead, and the widget goes into that page.
 * 2. That page brings its Content-Security-Policy along, and the site's policy allows only
 *    nonce-carrying inline scripts. The markup's own `<script>` has no nonce, so the injection reads
 *    the page's nonce and writes it onto the tags it adds.
 * 3. Loading a real page rather than a string means redirects and `www.`, so the host test is
 *    [isSameSite] rather than an equality: a strict one cancelled the redirect and nothing finished.
 * 4. `document.write`, which keeps the origin, is still the wrong container: Cloudflare's script
 *    returns a widget id and fills the container with its own nodes, but the `<iframe>` that actually
 *    runs the challenge never arrives — a silent failure, no error callback, an empty-looking box.
 *    A document written after load is not a document Cloudflare's later insertion can land in. The
 *    markup therefore goes into a `blob:` child frame: a normally-parsed document, still on the
 *    site's origin, and `frame-src blob:` is in the site's own policy.
 * 5. That still produced an empty child frame, because the navigation delegate cancelled it: the
 *    policy below asked "is this the site's host?" of a `blob:` URL, which has no host at all. Both
 *    the widget's frame and Cloudflare's own `<iframe>` are subframe loads, and the policy that is
 *    about not letting this view become a browser only ever needed to govern the top frame.
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
    // The bridge goes into every frame: the widget runs in a child frame, and it is that frame's
    // `window` the callbacks post through.
    controller.addUserScript(userScript(BRIDGE_SHIM, mainFrameOnly = false))
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

private fun userScript(source: String, mainFrameOnly: Boolean): WKUserScript =
    WKUserScript(
        source = source,
        injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
        forMainFrameOnly = mainFrameOnly,
    )

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

    /** The host the sitekey is registered against — `www.` and the bare domain are both it. */
    private val siteHost: String? = NSURL.URLWithString(NodeSeekSite.BASE_URL)?.host

    /**
     * The widget's frame posts here too: `frameInfo` is not consulted, because the only two kinds
     * that ever arrive from it are the ones the widget's own callbacks send.
     */
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
     * The site's own page has loaded — now put the widget into it.
     *
     * Once only: a second run would restart the challenge the user may already have passed. Anything
     * not on the site's own host is left alone — an interstitial served from elsewhere must not have
     * the widget put into it, or the sitekey would be rendered on a hostname it is not registered
     * for.
     */
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (hasWrittenDocument) return
        if (!isSameSite(webView.URL?.host, siteHost)) return
        hasWrittenDocument = true
        webView.evaluateJavaScript(widgetInjectionScript(document, sitekey, darkTheme), null)
    }

    /**
     * Main-frame navigations stay on the site's own host; subframes are let through.
     *
     * The rule exists so this view cannot become a browser, and that is a claim about where the
     * *top* frame ends up. Applying it to subframes is what cancelled the widget's own frame: a
     * `blob:` URL has no host at all, so the host test failed, the frame stayed an empty document,
     * and Cloudflare's script — which lives in that frame — never loaded.
     */
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        val staysOnSite =
            !isMainFrame || isSameSite(decidePolicyForNavigationAction.request.URL?.host, siteHost)
        decisionHandler(
            if (staysOnSite) {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }

    companion object {
        /**
         * Whether a host belongs to the site.
         *
         * `www.` and the bare domain are the same site to the sitekey and to the redirect that gets
         * there, but not to `==`. A strict equality cancelled the redirect from `BASE_URL` to its
         * `www.` form, and a cancelled redirect finishes nothing: `didFinish` never ran, so the
         * widget was never put anywhere.
         */
        fun isSameSite(host: String?, siteHost: String?): Boolean {
            if (host == null || siteHost == null) return false
            return host == siteHost || host.endsWith(".$siteHost") || siteHost.endsWith(".$host")
        }
    }
}

/**
 * Puts the widget into the site's own page, in a `blob:` child frame, and wires its two entry points.
 *
 * In order:
 * 1. Read the page's CSP nonce off one of its own `<script>` tags and hand it to the tags in the
 *    markup: the site's policy allows only nonce-carrying inline scripts, so without it the widget's
 *    own script is dropped on the floor.
 * 2. Build the markup into a `blob:` and point an `<iframe>` at it. That frame is a normally-parsed
 *    document on the site's origin, which is what Cloudflare's own insertion needs — a document
 *    produced by `document.write` after load is not (see the class KDoc, step 4).
 * 3. On load, redefine `nsTurnstileReady` and `nsTurnstileReset` inside that frame rather than
 *    trusting the markup's inline copy, which may or may not have been allowed through. Cloudflare's
 *    loader calls the former by name (`onload=…`), so it has to exist under exactly that name, and
 *    the loop keeps trying because that loader arrives asynchronously — a slow network is not a
 *    failure, it is a later tick.
 * 4. The top frame's `nsTurnstileReset` forwards in, because the reset signal arrives through
 *    `evaluateJavaScript` on the top frame.
 *
 * Everything sits in a `try`, and what it throws lands in the child frame's `__nsErr` — a variable,
 * not a read-out: nothing is painted, it is there for the next person who has to look.
 */
private fun widgetInjectionScript(document: String, sitekey: String, darkTheme: Boolean): String {
    val theme = if (darkTheme) "dark" else "light"
    return buildString {
        append("try{")
        append("var n='';var s=document.getElementsByTagName('script');")
        append("for(var i=0;i<s.length;i++){if(s[i].nonce){n=s[i].nonce;break;}}")
        // 只保证能看清、不裁剪；尺寸交给 Cloudflare 自己决定，别用 !important 去塑形。
        append("var extra='<style>html,body{height:auto!important;overflow:visible!important;background:transparent!important}")
        append("#w{min-height:0!important;padding:0!important;margin:0!important}</style>';")
        append("var h=").append(javaScriptStringLiteral(document)).append("+extra;")
        append("if(n){h=h.split('<script').join('<script nonce=\\\"'+n+'\\\"');}")
        append("var blob=new Blob([h],{type:'text/html'});")
        append("var frame=document.createElement('iframe');")
        append("frame.setAttribute('src',URL.createObjectURL(blob));")
        append("frame.setAttribute('style','width:100%;height:65px;border:0;display:block;background:transparent');")
        append("document.body.innerHTML='';document.body.appendChild(frame);")
        append("window.__nsFrame=frame;")
        // 量完再贴身：等 CF 画出真框后，把外层 iframe 收缩到它的实际高宽并居中，
        // 否则透明区域会露出底色，看上去就是「框比验证码大一圈」。
        append("var hug=function(){try{var d=frame.contentDocument;if(!d){return;}")
        append("var cf=d.getElementsByTagName('iframe')[0];if(!cf){return;}")
        append("var r=cf.getBoundingClientRect();if(!r||!r.height){return;}")
        append("var hp=Math.ceil(r.height),wp=Math.ceil(r.width);var host=d.getElementById('w');")
        append("if(host){host.style.minHeight='0';host.style.height=hp+'px';}")
        append("frame.style.height=hp+'px';if(wp>0){frame.style.width=wp+'px';frame.style.margin='0 auto';}}catch(e){}};")
        append("window.nsTurnstileReset=function(){try{frame.contentWindow.nsTurnstileReset();}catch(e){}};")
        append("var setup=function(){var w=frame.contentWindow;if(!w){return;}try{")
        append("w.nsTurnstileReset=function(){try{w.turnstile.reset();}catch(e){}};")
        append("w.nsTurnstileReady=function(){try{w.__nsWid=w.turnstile.render('#w',{")
        append("sitekey:").append(javaScriptStringLiteral(sitekey)).append(',')
        append("theme:").append(javaScriptStringLiteral(theme)).append(',')
        append("callback:function(t){w.").append(TURNSTILE_BRIDGE).append(".post('token',t);},")
        append("'expired-callback':function(){w.").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'timeout-callback':function(){w.").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'error-callback':function(e){w.__nsErr='cf: '+e;w.")
        append(TURNSTILE_BRIDGE).append(".post('error',String(e||''));}")
        append("});}catch(e){w.__nsErr='render: '+e;}w.__nsRendered=true;};")
        append("}catch(e){window.__nsErr='setup: '+e;}};")
        // 轮询到 CF 的脚本到位再渲染：只试一两次，慢网络下会白白错过。
        append("var tries=0;")
        append("var tick=function(){tries++;setup();hug();var w=frame.contentWindow;")
        append("if(w&&w.turnstile&&w.__nsWid===undefined&&typeof w.nsTurnstileReady==='function'){")
        append("try{w.nsTurnstileReady();}catch(e){window.__nsErr='ready: '+e;}}")
        append("if(tries<18){setTimeout(tick,700);}};")
        // 挑战通过后 CF 会重画（换成「成功！」那块），再补两次贴身。
        append("frame.onload=setup;setTimeout(tick,700);setTimeout(hug,5000);setTimeout(hug,12000);")
        append("}catch(e){window.__nsErr='inject: '+e;}")
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
