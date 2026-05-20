package com.phisher98.stealth

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled")
class StealthWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    companion object {
        const val TAG = "StealthWebView"
        
        // Clean mobile Chrome user-agent (no 'Version/4.0' or 'wv' string)
        const val MOBILE_CHROME_UA = "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    init {
        configureStealthSettings()
        clearSessionCookies()
    }

    private fun configureStealthSettings() {
        val settings = this.settings
        
        // Enable essential browser standards
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        // Enable cookie manager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        // Spoof standard mobile Chrome User-Agent
        settings.userAgentString = MOBILE_CHROME_UA
        
        // Disable WebView identify flags
        settings.mediaPlaybackRequiresUserGesture = false
    }

    private fun clearSessionCookies() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Injects custom stealth scripts into the page context.
     * This overrides navigator.webdriver, mocks window.chrome, and aligns canvas profiles.
     */
    fun injectAntiBotEvasionScript() {
        val stealthJs = """
            (function() {
                // Evasion 1: webdriver removal
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: () => false
                    });
                } catch (e) {}

                // Evasion 2: mock window.chrome
                window.chrome = {
                    runtime: {},
                    loadTimes: function() {},
                    csi: function() {},
                    app: {}
                };

                // Evasion 3: mock plugins length
                try {
                    Object.defineProperty(navigator, 'plugins', {
                        get: () => [1, 2, 3, 4, 5]
                    });
                } catch (e) {}

                // Evasion 4: mock permissions API behavior
                try {
                    const originalQuery = navigator.permissions.query;
                    navigator.permissions.query = (parameters) => 
                        parameters.name === 'notifications' ?
                            Promise.resolve({ state: Notification.permission }) :
                            originalQuery(parameters);
                } catch (e) {}
            })();
        """.trimIndent()
        
        this.evaluateJavascript(stealthJs, null)
    }

    /**
     * Programmatically dispatches native Android touch events over specified coordinates
     * simulating a genuine physical finger tap. Bypasses naive element click hooks.
     */
    fun simulateHumanTouch(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        // 1. Dispatch Action Down
        val actionDown = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0
        )
        this.dispatchTouchEvent(actionDown)
        actionDown.recycle()

        // 2. Dispatch Action Up (with slight delay mimicking human release latency)
        postDelayed({
            val actionUp = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            this.dispatchTouchEvent(actionUp)
            actionUp.recycle()
        }, 85)
    }
}
