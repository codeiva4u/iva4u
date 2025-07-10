package com.phisher98

import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

object MultiMoviesUtils {
    
    /**
     * Cloudflare और anti-bot संरक्षण के लिए उचित headers
     */
    val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )
    
    /**
     * JSON requests के लिए headers
     */
    val jsonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Content-Type" to "application/json"
    )
    
    /**
     * Safe GET request with proper headers
     */
    suspend fun safeGet(url: String, mainUrl: String, additionalHeaders: Map<String, String> = emptyMap()): Document {
        val headers = defaultHeaders.toMutableMap()
        headers["Referer"] = mainUrl
        headers.putAll(additionalHeaders)
        
        return try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            // यदि पहली कोशिश fail हो जाए, तो retry करें
            Thread.sleep(2000)
            app.get(url, headers = headers).document
        }
    }
    
    /**
     * Safe POST request with proper headers
     */
    suspend fun safePost(
        url: String, 
        mainUrl: String, 
        data: Map<String, String> = emptyMap(),
        additionalHeaders: Map<String, String> = emptyMap()
    ): Document {
        val headers = defaultHeaders.toMutableMap()
        headers["Referer"] = mainUrl
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers.putAll(additionalHeaders)
        
        return try {
            app.post(url, data = data, headers = headers).document
        } catch (e: Exception) {
            // यदि पहली कोशिश fail हो जाए, तो retry करें
            Thread.sleep(2000)
            app.post(url, data = data, headers = headers).document
        }
    }
    
    /**
     * Multiple selector fallback for finding elements
     */
    fun tryMultipleSelectors(document: Document, vararg selectors: String): org.jsoup.nodes.Element? {
        for (selector in selectors) {
            val element = document.selectFirst(selector)
            if (element != null) return element
        }
        return null
    }
    
    /**
     * Get image URL with multiple fallbacks
     */
    fun getImageUrl(element: org.jsoup.nodes.Element?): String? {
        if (element == null) return null
        
        val attributes = listOf("src", "data-src", "data-lazy", "data-original")
        for (attr in attributes) {
            val url = element.attr(attr)
            if (url.isNotEmpty() && url.startsWith("http")) {
                return url
            }
        }
        return null
    }
}
