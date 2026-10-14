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
 * Android's one call — `loadDataWithBaseURL` — does not translate, and the difference runs in three
 * steps, each of which is a way for this platform to lose the widget:
 *
 * 1. `loadHTMLString` documents have no origin. A sitekey is bound to the hostnames it may be
 *    rendered on, so such a widget is refused before the user sees a checkbox. The view is therefore
 *    navigated to the site's own https origin, and the markup is written into that page once it
 *    finishes — `document.write` keeps the creating origin.
 * 2. That page brings its Content-Security-Policy along, and the site's policy allows only
 *    nonce-carrying inline scripts. The markup's own `<script>` carries no nonce, so the injection
 *    hands the written tags the page's nonce and defines the two entry points itself.
 * 3. Loading a real page, rather than a string, means redirects and `www.` — a stricter host test
 *    than [isSameSite] cancels the redirect that lands on the real origin, and then nothing finishes.
 *
 * [DIAGNOSTIC_SHIM] is a temporary read-out: it paints the loaded URL and the widget's own state
 * across the top of the strip. It exists to be screenshotted on a device this project cannot build
 * for locally; remove it once the widget is confirmed on real hardware.
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
    for (source in listOf(BRIDGE_SHIM, DIAGNOSTIC_SHIM)) {
        controller.addUserScript(
            WKUserScript(
                source = source,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                // Both have to exist in the frame the widget's own script runs in, and only there.
                forMainFrameOnly = true,
            ),
        )
    }
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

    /** The host the sitekey is registered against — `www.` and the bare domain are both it. */
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
     * the challenge the user may already have passed. Anything not on the site's own host is left
     * alone — an interstitial served from elsewhere must not have the widget written into it, or the
     * sitekey would be rendered on a hostname it is not registered for.
     */
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (hasWrittenDocument) return
        if (!isSameSite(webView.URL?.host, siteHost)) return
        hasWrittenDocument = true
        webView.evaluateJavaScript(widgetInjectionScript(document, sitekey, darkTheme), null)
    }

    /**
     * The site's own host, and nothing else — see [isSameSite] for why it is not an equality test.
     *
     * The widget's own iframe is a subframe load and never reaches here.
     */
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val staysOnSite = isSameSite(decidePolicyForNavigationAction.request.URL?.host, siteHost)
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
 * Whether a host belongs to the site.
 *
 * `www.` and the bare domain are the same site to the sitekey and to the redirect that gets there,
 * but not to `==`. Comparing with a strict equality cancelled the redirect from `BASE_URL` to its
 * `www.` form, and a cancelled redirect finishes nothing: `didFinish` never ran, so the markup was
 * never written and the strip stayed empty.
 */
private fun isSameSite(host: String?, siteHost: String?): Boolean {
    if (host == null || siteHost == null) return false
    return host == siteHost || host.endsWith(".$siteHost") || siteHost.endsWith(".$host")
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
 *
 * The whole of it sits in a `try`, and whatever it throws lands in `__nsErr` — which is what
 * [DIAGNOSTIC_SHIM] paints, so a failure on a device says which one it was instead of showing a
 * blank strip.
 */
private fun widgetInjectionScript(document: String, sitekey: String, darkTheme: Boolean): String {
    val theme = if (darkTheme) "dark" else "light"
    return buildString {
        append("try{")
        append("var n='';var s=document.getElementsByTagName('script');")
        append("for(var i=0;i<s.length;i++){if(s[i].nonce){n=s[i].nonce;break;}}")
        // 文档末尾补一段样式：去掉 overflow 裁剪，并给 CF 的 iframe 一个明确尺寸 —— 兜住
        // "组件建出来了但被裁剪/零高度"这一类不可见。
        append("var extra='<style>html,body{overflow:visible!important}")
        append("#w iframe{width:100%!important;height:65px!important;border:0!important}</style>';")
        append("var h=").append(javaScriptStringLiteral(document)).append("+extra;")
        append("if(n){h=h.split('<script').join('<script nonce=\"'+n+'\"');}")
        append("document.open();document.write(h);document.close();")
        append(BRIDGE_SHIM)
        append("window.nsTurnstileReset=function(){try{turnstile.reset();}catch(e){}};")
        append("window.nsTurnstileReady=function(){try{window.__nsWid=turnstile.render('#w',{")
        append("sitekey:").append(javaScriptStringLiteral(sitekey)).append(',')
        append("theme:").append(javaScriptStringLiteral(theme)).append(',')
        append("callback:function(t){").append(TURNSTILE_BRIDGE).append(".post('token',t);},")
        append("'expired-callback':function(){").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'timeout-callback':function(){").append(TURNSTILE_BRIDGE).append(".post('expired','');},")
        append("'error-callback':function(e){window.__nsErr='cf: '+e;")
        append(TURNSTILE_BRIDGE).append(".post('error',String(e||''));}")
        append("});}catch(e){window.__nsErr='render: '+e;}window.__nsRendered=true;};")
        append("window.__nsErr=null;")
        // 兜底：Cloudflare 的加载器偶尔会比上面的定义先跑完，那它按名字调 onload 就没人应答 ——
        // 一秒半后自己补一次（带 rendered 标志，不会重复渲染）。
        append("setTimeout(function(){if(!window.__nsRendered){")
        append("try{window.nsTurnstileReady();}catch(e){window.__nsErr='retry: '+e;}}},1500);")
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

/**
 * Temporary: paints what this view actually loaded and what the widget thinks its state is.
 *
 * A `WKWebView` this size is invisible to every tool this project has — no log reaches a device that
 * only the user is holding, and this file cannot be built off a Mac. So the view reports on itself:
 * which URL finished, whether Cloudflare's script arrived, how many scripts the document has and
 * whether any of them carries a nonce, plus anything the injection threw. A screenshot of the strip
 * answers what a CI log would.
 */
private val DIAGNOSTIC_SHIM =
    """
    (function () {
      function read() {
        var scripts = document.getElementsByTagName('script');
        var holder = document.getElementById('w');
        var kids = 0;
        var shadow = 'n';
        if (holder) {
          kids = holder.getElementsByTagName('*').length;
          for (var j = 0; j < holder.children.length; j++) {
            var root = holder.children[j].shadowRoot;
            if (root) {
              shadow = 'y';
              kids += root.getElementsByTagName('*').length;
            }
          }
        }
        var frm = document.getElementsByTagName('iframe').length;
        var ifr = document.getElementsByTagName('iframe')[0];
        var size = 'none';
        if (ifr) {
          var r = ifr.getBoundingClientRect();
          size = Math.round(r.width) + 'x' + Math.round(r.height);
        }
        return 'rendered=' + (window.__nsRendered ? 'y' : 'n') +
               ' wid=' + (window.__nsWid === undefined ? 'undef' : String(window.__nsWid)) +
               ' frm=' + frm + '(' + size + ')' +
               ' kids=' + kids + ' sh=' + shadow +
               ' w=' + (holder ? holder.offsetWidth + 'x' + holder.offsetHeight : '-') +
               ' len=' + (holder ? holder.innerHTML.length : -1) +
               (window.__nsErr ? ' | ERR ' + window.__nsErr : '');
      }
      function paint() {
        var box = document.getElementById('nsDiag');
        if (!box) {
          box = document.createElement('div');
          box.id = 'nsDiag';
          box.setAttribute(
            'style',
            'position:fixed;left:0;top:0;width:100%;z-index:2147483647;background:rgba(0,0,0,0.85);' +
              'color:#6f6;font:8px/1.15 monospace;padding:1px;white-space:pre-wrap;pointer-events:none'
          );
          (document.body || document.documentElement).appendChild(box);
        }
        box.textContent = read();
      }
      window.addEventListener('error', function (e) { window.__nsErr = 'js: ' + e.message; });
      var ticks = 0;
      var timer = setInterval(function () { paint(); if (++ticks > 20) clearInterval(timer); }, 1000);
      paint();
    })();
    """.trimIndent()
