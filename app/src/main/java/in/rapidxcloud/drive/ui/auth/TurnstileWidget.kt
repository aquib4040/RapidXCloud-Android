package `in`.rapidxcloud.drive.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class TurnstileBridge(
    private val onTokenReceived: (String) -> Unit,
    private val onErrorReceived: () -> Unit,
    private val onExpiredReceived: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onTurnstileSuccess(token: String) {
        mainHandler.post { onTokenReceived(token) }
    }

    @JavascriptInterface
    fun onTurnstileError() {
        mainHandler.post { onErrorReceived() }
    }

    @JavascriptInterface
    fun onTurnstileExpired() {
        mainHandler.post { onExpiredReceived() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileWidget(
    siteKey: String = "0x4AAAAAADwA3BWuz2NPDaMQ",
    onVerify: (String) -> Unit,
    onError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isVerified by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    val htmlData = remember(siteKey) {
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
          <style>
            * { box-sizing: border-box; }
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              background-color: #0d0f14;
              display: flex;
              align-items: center;
              justify-content: center;
              overflow: hidden;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            }
            #container {
              display: flex;
              align-items: center;
              justify-content: center;
              width: 100%;
              min-height: 65px;
            }
          </style>
        </head>
        <body>
          <div id="container">
            <div id="cf-turnstile"></div>
          </div>
          <script>
            function initWidget() {
              if (window.turnstile) {
                try {
                  window.turnstile.render('#cf-turnstile', {
                    sitekey: '$siteKey',
                    theme: 'dark',
                    callback: function(token) {
                      if (window.AndroidBridge && window.AndroidBridge.onTurnstileSuccess) {
                        window.AndroidBridge.onTurnstileSuccess(token);
                      }
                    },
                    'error-callback': function() {
                      if (window.AndroidBridge && window.AndroidBridge.onTurnstileError) {
                        window.AndroidBridge.onTurnstileError();
                      }
                    },
                    'expired-callback': function() {
                      if (window.AndroidBridge && window.AndroidBridge.onTurnstileExpired) {
                        window.AndroidBridge.onTurnstileExpired();
                      }
                    }
                  });
                } catch(err) {
                  console.error('Turnstile render error:', err);
                }
              } else {
                setTimeout(initWidget, 100);
              }
            }
            window.addEventListener('load', initWidget);
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D0F14))
            .border(1.dp, if (isVerified) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0x14FFFFFF), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF0D0F14.toInt())

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowFileAccess = false
                        allowContentAccess = false
                        // Modern Android Chrome user agent to satisfy Cloudflare bot checks
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return true
                            // Only permit Cloudflare Turnstile and RapidXCloud origins
                            return !(host.endsWith("cloudflare.com") || host.endsWith("rapidxcloud.in"))
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            onError()
                        }
                    }

                    val bridge = TurnstileBridge(
                        onTokenReceived = { token ->
                            isVerified = true
                            onVerify(token)
                        },
                        onErrorReceived = {
                            isVerified = false
                            onError()
                        },
                        onExpiredReceived = {
                            isVerified = false
                            onVerify("")
                        }
                    )
                    addJavascriptInterface(bridge, "AndroidBridge")

                    loadDataWithBaseURL(
                        "https://auth.rapidxcloud.in/",
                        htmlData,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
            }
        )

        if (isVerified) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xCC0D0F14)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓ Cloudflare Verified",
                    color = Color(0xFF10B981),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
