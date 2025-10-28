package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI

// PixelDrain Extractor - Advanced with quality detection and logging
class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("PixelDrain", "Extracting from: $url")
            
            // Extract file ID using regex
            val fileId = Regex("/(?:u|file|l|api/file)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            
            if (fileId.isBlank()) {
                Log.d("PixelDrain", "Could not extract file ID")
                return
            }
            
            // Get file info for quality detection
            val apiUrls = listOf("https://pixeldrain.com", "https://pixeldrain.dev")
            
            for (apiBase in apiUrls) {
                try {
                    val infoUrl = "$apiBase/api/file/$fileId/info"
                    val response = app.get(infoUrl, timeout = 10L)
                    
                    if (response.isSuccessful) {
                        val jsonInfo = JSONObject(response.text)
                        val mimeType = jsonInfo.optString("mime_type", "")
                        val fileName = jsonInfo.optString("name", "Video")
                        val fileSize = jsonInfo.optLong("size", 0L)
                        
                        // Check if it's a video file
                        if (!mimeType.startsWith("video/", ignoreCase = true)) {
                            Log.d("PixelDrain", "Not a video file: $mimeType")
                            return
                        }
                        
                        // Format file size
                        val sizeStr = if (fileSize < 1024L * 1024 * 1024) {
                            "%.2f MB".format(fileSize.toDouble() / (1024 * 1024))
                        } else {
                            "%.2f GB".format(fileSize.toDouble() / (1024 * 1024 * 1024))
                        }
                        
                        // Detect quality from filename
                        val quality = when {
                            fileName.contains("2160p", ignoreCase = true) || fileName.contains("4K", ignoreCase = true) -> Qualities.P2160
                            fileName.contains("1440p", ignoreCase = true) -> Qualities.P1440
                            fileName.contains("1080p", ignoreCase = true) -> Qualities.P1080
                            fileName.contains("720p", ignoreCase = true) -> Qualities.P720
                            fileName.contains("480p", ignoreCase = true) -> Qualities.P480
                            fileName.contains("360p", ignoreCase = true) -> Qualities.P360
                            else -> Qualities.Unknown
                        }
                        
                        val directUrl = "$apiBase/api/file/$fileId"
                        
                        // Use loadExtractor as fallback for PixelDrain - simpler and more reliable
                        loadExtractor(directUrl, "$apiBase/", subtitleCallback, callback)
                        
                        Log.d("PixelDrain", "✓ Extracted: $fileName [Quality: $quality]")
                        return
                    }
                } catch (e: Exception) {
                    Log.d("PixelDrain", "Failed with $apiBase: ${e.message}")
                }
            }
            
            // Fallback to loadExtractor if API fails
            Log.d("PixelDrain", "Using fallback loadExtractor")
            loadExtractor(url, referer, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("PixelDrain", "Error: ${e.message}")
        }
    }
}

// HubCloud Extractor - Advanced with parallel processing and logging
open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) {
            Log.d(name, "Invalid URL: $url")
            return
        }
        
        try {
            Log.d(name, "Processing: $url")
            val doc = app.get(url, timeout = 10L).document
            
            // Get file info
            val header = doc.selectFirst("div.card-header")?.text() ?: "Video"
            val size = doc.selectFirst("i#size")?.text() ?: ""
            
            Log.d(name, "File: $header [$size]")
            
            // Find all download links
            val links = doc.select("a[href]").mapNotNull { link ->
                val href = link.attr("href")
                val text = link.text()
                
                when {
                    href.contains("pixeldrain", ignoreCase = true) -> href to "PixelDrain"
                    text.matches(Regex("(?i).*(server|download|gbps|fsl|zipdisk|instant|cloud).*")) && 
                    href.matches(Regex("https?://[\\w.-]+/.*")) &&
                    !href.contains("t.me") -> href to text
                    else -> null
                }
            }
            
            Log.d(name, "Found ${links.size} links")
            
            // Process links in parallel with amap
            links.amap { (linkUrl, linkName) ->
                try {
                    Log.d(name, "Extracting: $linkName")
                    loadExtractor(linkUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Failed to extract $linkName: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

// GDFlix Extractor - Advanced with parallel processing (No Telegram)
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) {
            Log.d(name, "Invalid URL: $url")
            return
        }
        
        try {
            Log.d(name, "Processing: $url")
            val doc = app.get(url, timeout = 10L).document
            
            // Find all download links (excluding Telegram)
            val links = doc.select("a[href]").mapNotNull { link ->
                val href = link.attr("href")
                val text = link.text()
                
                when {
                    href.contains("t.me", ignoreCase = true) -> {
                        Log.d(name, "Skipping Telegram link")
                        null
                    }
                    href.contains("pixeldrain", ignoreCase = true) -> href to "PixelDrain"
                    text.matches(Regex("(?i).*(instant|cloud|download|zipdisk|gofile|server).*")) && 
                    href.matches(Regex("https?://[\\w.-]+/.*")) -> href to text
                    else -> null
                }
            }
            
            Log.d(name, "Found ${links.size} links (Telegram excluded)")
            
            // Process links in parallel with amap
            links.amap { (linkUrl, linkName) ->
                try {
                    Log.d(name, "Extracting: $linkName")
                    loadExtractor(linkUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Failed to extract $linkName: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

// fastdlserver - Fast redirect handler with logging
open class fastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://pixel.hubcdn.fans/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Fast extracting: $url")
            loadExtractor(url, referer, subtitleCallback, callback)
            Log.d(name, "✓ Extraction complete")
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}
