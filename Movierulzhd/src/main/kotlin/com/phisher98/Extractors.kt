package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * CherryExtractor - Improved implementation for cherry.upns.online
 * Handles Vidstack player and extracts actual video sources
 */
open class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = true

    data class VideoSource(
        @JsonProperty("src") val src: String?,
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("label") val label: String?
    )
    
    data class SourceResponse(
        @JsonProperty("data") val data: List<VideoSource>?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("CherryExtractor", "=== START EXTRACTION ===")
        Log.d("CherryExtractor", "URL: $url")
        Log.d("CherryExtractor", "Referer: $referer")
        
        try {
            // Extract hash from URL (e.g., #lopb3d from https://cherry.upns.online/#lopb3d)
            val hash = url.substringAfter("#", "")
            if (hash.isEmpty()) {
                Log.e("CherryExtractor", "No hash found in URL")
                return
            }
            
            Log.d("CherryExtractor", "Extracted hash: $hash")
            
            // Fetch the page
            val response = app.get(
                url = url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to (referer ?: mainUrl),
                    "Origin" to mainUrl
                ),
                allowRedirects = true
            )
            
            val html = response.text
            Log.d("CherryExtractor", "Page loaded, size: ${html.length} bytes")
            
            var foundCount = 0
            
            // Method 1: Try API endpoint for video sources
            try {
                val apiUrl = "$mainUrl/api/source/$hash"
                Log.d("CherryExtractor", "Trying API: $apiUrl")
                
                val apiResponse = app.get(
                    url = apiUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                        "Accept" to "application/json",
                        "Referer" to url,
                        "Origin" to mainUrl
                    )
                )
                
                val responseText = apiResponse.text
                Log.d("CherryExtractor", "API Response: $responseText")
                
                // Try to parse as array or object
                val sources = try {
                    AppUtils.parseJson<List<VideoSource>>(responseText)
                } catch (e: Exception) {
                    try {
                        val wrapper = AppUtils.parseJson<SourceResponse>(responseText)
                        wrapper.data ?: emptyList()
                    } catch (e2: Exception) {
                        Log.e("CherryExtractor", "Failed to parse API response: ${e2.message}")
                        emptyList()
                    }
                }
                
                sources.forEach { source ->
                    val videoUrl = source.src ?: source.file
                    videoUrl?.let {
                        val quality = source.quality ?: source.label ?: "Unknown"
                        Log.d("CherryExtractor", "Found API source: $videoUrl ($quality)")
                        
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "$name $quality",
                                streamUrl = videoUrl,
                                referer = url,
                                headers = mapOf("Origin" to mainUrl)
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = "$name $quality",
                                    name = "$name $quality",
                                    url = videoUrl
                                ) {
                                    this.referer = url
                                }
                            )
                        }
                        foundCount++
                    }
                }
            } catch (e: Exception) {
                Log.e("CherryExtractor", "API method failed: ${e.message}")
            }
            
            // Method 2: Extract from HTML/JavaScript
            if (foundCount == 0) {
                Log.d("CherryExtractor", "Trying HTML extraction...")
                
                // Look for m3u8 URLs
                val m3u8Pattern = """(https?://[^\s"'<>\)\(\[\]{}]+\.m3u8[^\s"'<>\)\(\[\]{}]*)""".toRegex()
                m3u8Pattern.findAll(html).forEach { match ->
                    val m3u8Url = match.groupValues[1].trim().replace("\\/", "/")
                    if (m3u8Url.isNotEmpty()) {
                        Log.d("CherryExtractor", "Found M3U8: $m3u8Url")
                        try {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = m3u8Url,
                                referer = url,
                                headers = mapOf("Origin" to mainUrl)
                            ).forEach(callback)
                            foundCount++
                        } catch (e: Exception) {
                            Log.e("CherryExtractor", "M3U8 error: ${e.message}")
                        }
                    }
                }
                
                // Look for MP4 URLs
                val mp4Pattern = """(https?://[^\s"'<>\)\(\[\]{}]+\.mp4[^\s"'<>\)\(\[\]{}]*)""".toRegex()
                mp4Pattern.findAll(html).forEach { match ->
                    val mp4Url = match.groupValues[1].trim().replace("\\/", "/")
                    if (mp4Url.isNotEmpty()) {
                        Log.d("CherryExtractor", "Found MP4: $mp4Url")
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name MP4",
                                    url = mp4Url
                                ) {
                                    this.referer = url
                                }
                            )
                            foundCount++
                        } catch (e: Exception) {
                            Log.e("CherryExtractor", "MP4 error: ${e.message}")
                        }
                    }
                }
            }
            
            // Method 3: Try common API patterns
            if (foundCount == 0) {
                Log.d("CherryExtractor", "Trying alternate API endpoints...")
                val endpoints = listOf(
                    "$mainUrl/source/$hash",
                    "$mainUrl/sources/$hash",
                    "$mainUrl/api/$hash",
                    "$mainUrl/player/$hash"
                )
                
                for (endpoint in endpoints) {
                    try {
                        Log.d("CherryExtractor", "Trying: $endpoint")
                        val resp = app.get(
                            url = endpoint,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                                "Accept" to "application/json, text/plain, */*",
                                "Referer" to url,
                                "Origin" to mainUrl
                            )
                        )
                        
                        val text = resp.text
                        if (text.contains(".m3u8") || text.contains(".mp4")) {
                            Log.d("CherryExtractor", "Found potential source at $endpoint: ${text.take(200)}")
                            
                            // Extract any video URLs from response
                            val videoPattern = """(https?://[^\s"'<>]+\.(m3u8|mp4|mkv)[^\s"'<>]*)""".toRegex()
                            videoPattern.findAll(text).forEach { match ->
                                val videoUrl = match.groupValues[1].replace("\\/", "/").trim()
                                if (videoUrl.isNotEmpty()) {
                                    Log.d("CherryExtractor", "Extracted video: $videoUrl")
                                    try {
                                        if (videoUrl.contains(".m3u8")) {
                                            M3u8Helper.generateM3u8(
                                                source = name,
                                                streamUrl = videoUrl,
                                                referer = url,
                                                headers = mapOf("Origin" to mainUrl)
                                            ).forEach(callback)
                                        } else {
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = name,
                                                    name = name,
                                                    url = videoUrl
                                                ) {
                                                    this.referer = url
                                                }
                                            )
                                        }
                                        foundCount++
                                    } catch (e: Exception) {
                                        Log.e("CherryExtractor", "Error adding video: ${e.message}")
                                    }
                                }
                            }
                            break
                        }
                    } catch (e: Exception) {
                        Log.d("CherryExtractor", "Endpoint $endpoint failed: ${e.message}")
                    }
                }
            }
            
            Log.d("CherryExtractor", "=== EXTRACTION COMPLETE ===")
            Log.d("CherryExtractor", "Total links found: $foundCount")
            
        } catch (e: Exception) {
            Log.e("CherryExtractor", "=== EXTRACTION FAILED ===")
            Log.e("CherryExtractor", "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
