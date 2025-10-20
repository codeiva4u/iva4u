package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

/**
 * CherryExtractor - Movierulzhd की primary video hosting service
 * 
 * Host: cherry.upns.online
 * Technology: Vidstack Player v16.5.3
 * 
 * New Implementation Strategy (Without WebView):
 *   1. Direct HTTP request to Cherry page
 *   2. Extract video ID from URL hash
 *   3. Parse JavaScript modules and embedded data
 *   4. Extract video sources from Vidstack player configuration
 *   5. Support for HLS (m3u8) and direct video (mp4/mkv)
 */
open class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("CherryExtractor", "Starting extraction from: $url")
            
            // Extract video ID from URL (e.g., "lopb3d" from "https://cherry.upns.online/#lopb3d")
            val videoId = url.substringAfter("#").substringBefore("&")
            Log.d("CherryExtractor", "Video ID: $videoId")
            
            // Custom headers
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Origin" to mainUrl,
                "Referer" to (referer ?: mainUrl),
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            
            // Fetch the page
            val response = app.get(url, headers = headers)
            val document = response.document
            val pageHtml = response.text
            
            Log.d("CherryExtractor", "Page loaded, parsing content...")
            
            // Try multiple extraction methods
            var foundLinks = false
            
            // Method 1: Extract from JavaScript modules
            foundLinks = extractFromModules(pageHtml, videoId, url, headers, callback) || foundLinks
            
            // Method 2: Extract from inline scripts  
            foundLinks = extractFromScripts(document, url, headers, callback) || foundLinks
            
            // Method 3: Try API endpoint (if video ID is available)
            if (!foundLinks && videoId.isNotEmpty()) {
                foundLinks = tryApiEndpoint(videoId, url, headers, callback) || foundLinks
            }
            
            if (!foundLinks) {
                Log.e("CherryExtractor", "No video links found after all extraction attempts")
            } else {
                Log.d("CherryExtractor", "Successfully extracted video links")
            }
            
        } catch (e: Exception) {
            Log.e("CherryExtractor", "Fatal extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // Method 1: Extract from JavaScript modules
    private suspend fun extractFromModules(
        pageHtml: String,
        videoId: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Look for module imports that might contain video data
            val modulePattern = """src=["'](/assets/[^"']+\.js)["']""".toRegex()
            val matches = modulePattern.findAll(pageHtml)
            
            for (match in matches) {
                val modulePath = match.groupValues[1]
                if (modulePath.isNotEmpty()) {
                    val moduleUrl = "$mainUrl$modulePath"
                    Log.d("CherryExtractor", "Checking module: $moduleUrl")
                    
                    try {
                        val moduleContent = app.get(moduleUrl, headers = headers).text
                        if (extractVideoUrlsFromScript(moduleContent, referer, callback)) {
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e("CherryExtractor", "Failed to fetch module: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CherryExtractor", "Module extraction error: ${e.message}")
        }
        return false
    }
    
    // Method 2: Extract from inline scripts
    private suspend fun extractFromScripts(
        document: Document,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            // Skip ad scripts
            if (scriptData.contains("girlieturtle") || scriptData.contains("managerX")) {
                return@forEach
            }
            
            if (extractVideoUrlsFromScript(scriptData, referer, callback)) {
                found = true
            }
        }
        
        return found
    }
    
    // Method 3: Try API endpoint
    private suspend fun tryApiEndpoint(
        videoId: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Try common API patterns
            val apiUrls = listOf(
                "$mainUrl/api/source/$videoId",
                "$mainUrl/api/video/$videoId",
                "$mainUrl/source/$videoId",
                "$mainUrl/get/$videoId"
            )
            
            for (apiUrl in apiUrls) {
                try {
                    Log.d("CherryExtractor", "Trying API: $apiUrl")
                    val response = app.get(apiUrl, headers = headers)
                    
                    if (response.code < 400) {
                        val data = response.text
                        if (extractVideoUrlsFromScript(data, referer, callback)) {
                            return true
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next API URL
                }
            }
        } catch (e: Exception) {
            Log.e("CherryExtractor", "API extraction error: ${e.message}")
        }
        return false
    }
    
    // Common method to extract video URLs from any script content
    private suspend fun extractVideoUrlsFromScript(
        scriptData: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // Enhanced regex patterns for video URLs
        val patterns = listOf(
            // Pattern 1: Standard video URLs
            """(https?://[^\s"'<>{}\[\]]+\.(m3u8|mp4|mkv|ts)[^\s"'<>{}\[\]]*)["\'\s<>]""".toRegex(),
            // Pattern 2: Quoted URLs
            """["']([^"']*\.(m3u8|mp4|mkv|ts)[^"']*)["']""".toRegex(),
            // Pattern 3: Source/src/url patterns
            """(?:src|source|url|file)[:\s=]+["']([^"']+\.(m3u8|mp4|mkv|ts)[^"']*)["']""".toRegex(),
            // Pattern 4: Vidstack specific patterns
            """(?:streamUrl|videoUrl)["']?[:\s=]+["']([^"']+)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            pattern.findAll(scriptData).forEach { match ->
                var videoUrl = match.groupValues[1]
                    .replace("\\\\", "/")
                    .replace("\\/", "/")
                    .trim()
                
                // Clean up the URL
                if (videoUrl.startsWith("//")) {
                    videoUrl = "https:$videoUrl"
                }
                
                // Validate and add the URL
                if (videoUrl.startsWith("http") && 
                    (videoUrl.contains(".m3u8") || 
                     videoUrl.contains(".mp4") || 
                     videoUrl.contains(".mkv") ||
                     videoUrl.contains(".ts"))) {
                    
                    Log.d("CherryExtractor", "Found video URL: $videoUrl")
                    
                    try {
                        when {
                            videoUrl.contains(".m3u8") -> {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = videoUrl,
                                    referer = referer
                                ).forEach(callback)
                                found = true
                            }
                            else -> {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = videoUrl
                                    ) {
                                        this.referer = referer
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CherryExtractor", "Error adding video URL: ${e.message}")
                    }
                }
            }
        }
        
        return found
    }
}
