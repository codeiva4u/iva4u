package com.phisher98.stealth

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class CookieSyncBridge : CookieJar {

    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        for (cookie in cookies) {
            cookieManager.setCookie(urlString, cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()
        val cookies = ArrayList<Cookie>()
        
        // Parse the raw cookie string (format: "name1=value1; name2=value2")
        val pairs = cookieString.split(";")
        for (pair in pairs) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty() || !trimmed.contains("=")) continue
            
            try {
                val cookie = Cookie.parse(url, trimmed)
                if (cookie != null) {
                    cookies.add(cookie)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return cookies
    }
}
