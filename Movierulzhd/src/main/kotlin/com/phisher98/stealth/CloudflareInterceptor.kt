package com.phisher98.stealth

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if response is blocked by Cloudflare (403 Forbidden or 503 Service Unavailable)
        val serverHeader = response.header("server") ?: ""
        val isCloudflare = serverHeader.contains("cloudflare", ignoreCase = true)
        val isBlocked = response.code == 403 || response.code == 503

        if (isBlocked && isCloudflare) {
            response.close() // Close the current blocked stream
            
            // Acquire solved cookies by running StealthWebView on the main thread
            val cookiesAcquired = acquireCloudflareClearance(request.url.toString())
            
            if (cookiesAcquired) {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookieString = cookieManager.getCookie(request.url.toString())
                val builder = request.newBuilder()
                if (!cookieString.isNullOrEmpty()) {
                    builder.header("Cookie", cookieString)
                }
                return chain.proceed(builder.build())
            }
        }

        return response
    }

    private fun acquireCloudflareClearance(url: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        mainHandler.post {
            try {
                val currentActivity = context as? Activity
                if (currentActivity == null || currentActivity.isFinishing || currentActivity.isDestroyed) {
                    latch.countDown()
                    return@post
                }

                // Create the StealthWebView instance dynamically
                val stealthWebView = StealthWebView(context)
                
                // Configure and attach the StealthWebViewClient
                val client = StealthWebViewClient(stealthWebView) {
                    success = true
                    // Remove the WebView from parent container upon completion
                    removeWebViewSafely(currentActivity, stealthWebView)
                    latch.countDown()
                }
                stealthWebView.webViewClient = client

                // Inject the webview into the activity view hierarchy invisibly (1x1 size)
                val params = FrameLayout.LayoutParams(1, 1)
                val rootLayout = currentActivity.findViewById<ViewGroup>(android.R.id.content)
                rootLayout.addView(stealthWebView, params)

                // Load the target blocked URL
                stealthWebView.loadUrl(url)

            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }

        try {
            // Block the background network thread for max 40 seconds to solve Turnstile
            latch.await(40, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return success
    }

    private fun removeWebViewSafely(activity: Activity, webView: StealthWebView) {
        mainHandler.post {
            try {
                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)
                rootLayout.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
