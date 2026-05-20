package com.phisher98.stealth

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class StealthWebViewClient(
    private val webView: StealthWebView,
    private val onCaptchaSolvedListener: () -> Unit
) : WebViewClient() {

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Register the javascript interface to receive challenge coordinate reports
        webView.addJavascriptInterface(TurnstileLocatorInterface(), "TurnstileLocator")
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject anti-bot scripts as early as possible
        webView.injectAntiBotEvasionScript()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Inject again on load completion to cover dynamic SPA navigation
        webView.injectAntiBotEvasionScript()
        
        // Scan the page for Cloudflare Turnstile challenges
        startTurnstileScanLoop()
    }

    private fun startTurnstileScanLoop() {
        val locatorScript = """
            (function() {
                function scan() {
                    const iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                    if (iframe) {
                        const rect = iframe.getBoundingClientRect();
                        // Turnstile checkbox typically resides near the middle-left of the challenges frame
                        const targetX = window.scrollX + rect.left + 50; 
                        const targetY = window.scrollY + rect.top + (rect.height / 2);
                        
                        // Send the coordinates back to Android native handler
                        TurnstileLocator.onChallengeFound(targetX, targetY);
                        return true;
                    }
                    return false;
                }
                
                // Perform initial check and retry if page is still loading
                if (!scan()) {
                    let attempts = 0;
                    const interval = setInterval(() => {
                        attempts++;
                        if (scan() || attempts > 10) {
                            clearInterval(interval);
                        }
                    }, 1000);
                }
            })();
        """.trimIndent()

        mainHandler.postDelayed({
            webView.evaluateJavascript(locatorScript, null)
        }, 1500)
    }

    /**
     * JavaScript Interface to receive Turnstile checkbox coordinates and automate clicks.
     */
    inner class TurnstileLocatorInterface {
        @JavascriptInterface
        fun onChallengeFound(x: Float, y: Float) {
            Log.d("StealthWebView", "Found Turnstile Challenge at coordinates: X: $x, Y: $y")
            
            // Execute simulated physical click on the main UI thread with natural latency
            mainHandler.postDelayed({
                webView.simulateHumanTouch(x, y)
            }, 800)
            
            // Check for success page title or cookie changes to trigger solver listener
            startCompletionPoll()
        }
    }

    private fun startCompletionPoll() {
        val checkCookieScript = """
            (function() {
                return document.cookie.includes('cf_clearance');
            })();
        """.trimIndent()

        var pollCount = 0
        val checkRunnable = object : Runnable {
            override fun run() {
                pollCount++
                webView.evaluateJavascript(checkCookieScript) { result ->
                    val isSolved = result?.toBoolean() ?: false
                    if (isSolved) {
                        Log.d("StealthWebView", "Cloudflare Turnstile Solved Successfully!")
                        onCaptchaSolvedListener()
                    } else if (pollCount < 15) {
                        // Reschedule checking
                        mainHandler.postDelayed(this, 1000)
                    }
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 2000)
    }
}
