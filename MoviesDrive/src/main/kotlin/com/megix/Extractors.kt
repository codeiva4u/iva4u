package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


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
            // Extract file ID from various Pixeldrain URL formats
            // Supports: /u/ID, /file/ID, /l/ID, api/file/ID, etc.
            val mId = Regex("/(?:u|file|l|api/file)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            
            if (mId.isBlank()) {
                Log.d("PixelDrain", "Could not extract file ID from: $url")
                return
            }
            
            // Try multiple API endpoints for compatibility
            val apiUrls = listOf(
                "https://pixeldrain.com/api/file/$mId",
                "https://pixeldrain.dev/api/file/$mId"
            )
            
            // Get file info first to check if it's a valid video
            for (apiBase in listOf("https://pixeldrain.com", "https://pixeldrain.dev")) {
                try {
                    val infoResponse = app.get("$apiBase/api/file/$mId/info", allowRedirects = false)
                    if (infoResponse.isSuccessful) {
                        val infoText = infoResponse.text
                        val jsonInfo = JSONObject(infoText)
                        val mimeType = jsonInfo.optString("mime_type", "")
                        val fileName = jsonInfo.optString("name", "Video")
                        val fileSize = jsonInfo.optLong("size", 0L)
                        
                        // Format size
                        val formattedSize = if (fileSize < 1024L * 1024 * 1024) {
                            "%.2f MB".format(fileSize.toDouble() / (1024 * 1024))
                        } else {
                            "%.2f GB".format(fileSize.toDouble() / (1024 * 1024 * 1024))
                        }
                        
                        // Only process if it's a video file
                        if (mimeType.startsWith("video/", ignoreCase = true)) {
                            // Use direct download URL without ?download parameter for better compatibility
                            val finalUrl = "$apiBase/api/file/$mId"
                            
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "${this.name} $fileName [$formattedSize]",
                                    finalUrl
                                ) {
                                    this.referer = "$apiBase/"
                                }
                            )
                            Log.d("PixelDrain", "Successfully extracted: $fileName")
                            return // Success, exit
                        } else {
                            Log.d("PixelDrain", "File is not a video. MIME type: $mimeType")
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.d("PixelDrain", "Failed with $apiBase: ${e.message}")
                    // Try next API base
                }
            }
        } catch (e: Exception) {
            Log.e("PixelDrain", "Error extracting: ${e.message}")
        }
    }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.one"
    override val requiresReferer = false

    fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Validate URL before processing
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("null", ignoreCase = true)) {
            Log.d("HubCloud", "Invalid URL skipped: $url")
            return // Invalid URL or null source, skip processing
        }
        
        try {
            val doc = app.get(url).document
            
            // Extract file ID from URL
            val fileId = url.substringAfterLast("/")
            
            // Get file info
            val header = doc.selectFirst("div.card-header")?.text() ?: "Video"
            val size = doc.selectFirst("i#size")?.text() ?: ""

            Log.d("HubCloud", "Processing: $header [$size]")

            // Check if this is hubcloud page with gamerxyt redirect
            val gamerxytLink = doc.selectFirst("a#download[href*=gamerxyt], a[href*=gamerxyt]")?.attr("href")
            
            if (gamerxytLink != null && gamerxytLink.isNotBlank()) {
                // This is initial HubCloud page - follow gamerxyt to get actual servers
                try {
                    Log.d("HubCloud", "Following gamerxyt redirect: $gamerxytLink")
                    
                    // Get the redirect page with all servers
                    val redirectDoc = app.get(gamerxytLink).document
                    val redirectHeader = redirectDoc.selectFirst("div.card-header")?.text() ?: header
                    val redirectSize = redirectDoc.selectFirst("i#size")?.text() ?: size
                    
                    // Extract S3 Server URL from JavaScript  
                    val s3Link = redirectDoc.select("script").firstOrNull { 
                        it.html().contains("window.location.href") && it.html().contains("s3.blockxpiracy.net")
                    }?.let { script ->
                        Regex("window\\.location\\.href\\s*=\\s*['\"]((https://s3\\.blockxpiracy\\.net[^'\"]+)['\"])").find(script.html())?.groupValues?.getOrNull(1)
                    }
                    
                    Log.d("HubCloud", "S3 Link extracted: ${s3Link?.take(80) ?: "None"}")
                    
                    // Extract ALL server buttons from redirect page
                    var serverCount = 0
                    redirectDoc.select("a.btn, button.btn").forEach { serverBtn ->
                        val serverLink = serverBtn.attr("href")
                        val serverText = serverBtn.text()
                        
                        // Skip empty links or javascript actions
                        if (serverLink.isBlank() || serverLink.startsWith("#") || serverLink.startsWith("javascript:")) {
                            return@forEach
                        }
                        
                        when {
                            // PixelServer - PixelDrain direct link
                            serverText.contains("PixelServer", ignoreCase = true) || serverLink.contains("pixeldrain", ignoreCase = true) -> {
                                try {
                                    PixelDrain().getUrl(serverLink, url, subtitleCallback, callback)
                                    serverCount++
                                    Log.d("HubCloud", "[$serverCount] Processing PixelDrain: $serverLink")
                                } catch (e: Exception) {
                                    Log.e("HubCloud", "PixelDrain error: ${e.message}")
                                }
                            }
                            
                            // 10Gbps Server - pixel.hubcdn.fans
                            serverText.contains("10Gbps", ignoreCase = true) || serverText.contains("10 Gbps", ignoreCase = true) -> {
                                callback.invoke(
                                    newExtractorLink("$name[10Gbps⚡]", "$name 10Gbps $redirectHeader [$redirectSize]", serverLink)
                                )
                                serverCount++
                                Log.d("HubCloud", "[$serverCount] Added 10Gbps: ${serverLink.take(60)}...")
                            }
                            
                            // FSL Server - fsl.anime4u.co
                            serverText.contains("FSL Server", ignoreCase = true) -> {
                                callback.invoke(
                                    newExtractorLink("$name[FSL]", "$name FSL $redirectHeader [$redirectSize]", serverLink)
                                )
                                serverCount++
                                Log.d("HubCloud", "[$serverCount] Added FSL: ${serverLink.take(60)}...")
                            }
                            
                            // S3 Server - from JavaScript redirect (s3.blockxpiracy.net)
                            serverText.contains("S3 Server", ignoreCase = true) -> {
                                if (s3Link != null) {
                                    callback.invoke(
                                        newExtractorLink("$name[S3]", "$name S3 $redirectHeader [$redirectSize]", s3Link)
                                    )
                                    serverCount++
                                    Log.d("HubCloud", "[$serverCount] Added S3: ${s3Link.take(60)}...")
                                } else {
                                    Log.d("HubCloud", "S3 button found but URL not extracted from JS")
                                }
                            }
                            
                            // ZipDisk Server - Cloudflare Workers with .zip
                            serverText.contains("ZipDisk", ignoreCase = true) -> {
                                callback.invoke(
                                    newExtractorLink("$name[ZipDisk]", "$name ZipDisk $redirectHeader [$redirectSize] [ZIP]", serverLink)
                                )
                                serverCount++
                                Log.d("HubCloud", "[$serverCount] Added ZipDisk: ${serverLink.take(60)}...")
                            }
                        }
                    }
                    
                    Log.d("HubCloud", "Total servers extracted: $serverCount")
                    // Return after processing gamerxyt page
                    return
                } catch (e: Exception) {
                    Log.e("HubCloud", "Error processing gamerxyt redirect: ${e.message}")
                }
            }

            // Fallback: Also check for alternative download buttons on current page
            val div = doc.selectFirst("div.card-body")
            div?.select("h2 a.btn, a.btn")?.filter { !it.attr("href").contains("gamerxyt") }?.forEach {
                val btnLink = it.attr("href")
                val text = it.text()

                when {
                    text.contains("PixelDrain", ignoreCase = true) || btnLink.contains("pixeldra", ignoreCase = true) -> {
                        try {
                            PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("HubCloud", "Pixeldrain extraction failed: ${e.message}")
                        }
                    }
                    
                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(name, "$name $header[$size]", btnLink)
                        )
                    }

                    else -> {
                        try {
                            loadExtractor(btnLink, "", subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("HubCloud", "LoadExtractor Error: ${e.localizedMessage}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Main extraction error: ${e.message}")
        }
    }
}

open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Validate URL before processing
            if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("null", ignoreCase = true)) {
                Log.d("GDFlix", "Invalid URL skipped: $url")
                return
            }
            
            val document = app.get(url).document
            
            // Extract video title and size from meta/header or page title
            val ogDesc = document.selectFirst("meta[property=og:description]")?.attr("content")
            val pageTitle = document.selectFirst("title")?.text() ?: ""
            val title = ogDesc?.substringBefore(" - ") ?: pageTitle.substringBefore(" - ").ifEmpty { "Video" }
            
            // Extract file size if available
            val sizeMatch = Regex("\\[([\\d.]+\\s*[KMGT]B)\\]").find(pageTitle) 
                ?: Regex("([\\d.]+\\s*[KMGT]B)").find(pageTitle)
            val size = sizeMatch?.groupValues?.get(1) ?: ""
            
            Log.d("GDFlix", "Processing: $title [$size]")
            
            // Find all download buttons  
            val downloadButtons = document.select("a.btn, a[class*=btn]")
            
            var serverCount = 0
            downloadButtons.forEach { button ->
                val btnText = button.text()
                val btnLink = button.attr("href")
                
                // Skip empty or invalid links
                if (btnLink.isBlank() || btnLink == "#") return@forEach
                
                when {
                    // Instant DL - Encrypted link from busycdn - needs redirect following
                    btnText.contains("Instant", ignoreCase = true) && btnLink.contains("busycdn") -> {
                        try {
                            // Follow entire redirect chain to get actual download URL
                            // busycdn.cfd -> fastcdn-dl.pages.dev -> video URL (Google/R2)
                            val redirectResponse = app.get(btnLink, allowRedirects = true)
                            val redirectUrl = redirectResponse.url
                            
                            Log.d("GDFlix", "Instant DL redirected to: ${redirectUrl.take(80)}...")
                            
                            // Check if we landed on fastcdn-dl redirect page
                            if (redirectUrl.contains("fastcdn-dl", ignoreCase = true)) {
                                // Extract the actual video URL from query parameter
                                val actualVideoUrl = Regex("[?&]url=([^&]+)").find(redirectUrl)?.groupValues?.getOrNull(1)
                                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                                
                                if (actualVideoUrl != null && actualVideoUrl.isNotBlank()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            "$name[Instant⚡]",
                                            "$name Instant DL $title [$size]",
                                            actualVideoUrl
                                        ) {
                                            this.referer = "https://fastcdn-dl.pages.dev/"
                                        }
                                    )
                                    serverCount++
                                    Log.d("GDFlix", "[$serverCount] Successfully extracted Instant DL: ${actualVideoUrl.take(60)}...")
                                } else {
                                    Log.d("GDFlix", "Could not extract video URL from fastcdn redirect")
                                }
                            } else if (!redirectUrl.contains("busycdn", ignoreCase = true)) {
                                // Direct video URL obtained
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[Instant⚡]",
                                        "$name Instant DL $title [$size]",
                                        redirectUrl
                                    ) {
                                        this.referer = mainUrl
                                    }
                                )
                                serverCount++
                                Log.d("GDFlix", "[$serverCount] Direct Instant DL extracted: ${redirectUrl.take(60)}...")
                            } else {
                                Log.d("GDFlix", "Instant link still on redirect page: $redirectUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Instant DL error: ${e.message}")
                        }
                    }
                    
                    // CLOUD DOWNLOAD [R2] - Direct R2 link via fastcdn-dl
                    btnText.contains("CLOUD DOWNLOAD", ignoreCase = true) && btnLink.contains("fastcdn-dl") -> {
                        try {
                            // Extract actual R2 URL from fastcdn-dl query parameter
                            val r2Url = Regex("[?&]url=([^&]+)").find(btnLink)?.groupValues?.getOrNull(1)
                                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                            
                            if (r2Url != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[R2Cloud]",
                                        "$name R2 Cloud $title [$size]",
                                        r2Url
                                    ) {
                                        this.referer = "https://fastcdn-dl.pages.dev/"
                                    }
                                )
                                serverCount++
                                Log.d("GDFlix", "[$serverCount] Added R2 Cloud: ${r2Url.take(60)}...")
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "R2 Cloud error: ${e.message}")
                        }
                    }
                    
                    // PixelDrain - Already working extractor
                    btnLink.contains("pixeldra", ignoreCase = true) -> {
                        try {
                            PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                            serverCount++
                            Log.d("GDFlix", "[$serverCount] Processing PixelDrain")
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Pixeldrain extraction failed: ${e.message}")
                        }
                    }
                    
                    // Fast Cloud/ZipDisk - GDFlix internal servers - follow redirects
                    btnText.contains("FAST CLOUD", ignoreCase = true) || 
                    btnText.contains("ZIPDISK", ignoreCase = true) ||
                    btnLink.contains("/zfile/", ignoreCase = true) -> {
                        try {
                            val cloudResponse = app.get(btnLink, allowRedirects = true)
                            val cloudUrl = cloudResponse.url
                            
                            callback.invoke(
                                newExtractorLink(
                                    "$name[Cloud]",
                                    "$name Cloud DL $title [$size]",
                                    cloudUrl
                                ) {
                                    this.referer = btnLink
                                }
                            )
                            serverCount++
                            Log.d("GDFlix", "[$serverCount] Added Cloud/ZipDisk")
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Cloud DL error: ${e.message}")
                        }
                    }
                    
                    // GoFile Mirror
                    btnLink.contains("goflix", ignoreCase = true) || 
                    btnLink.contains("gofile", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name[GoFile]",
                                    "$name GoFile $title [$size]",
                                    btnLink
                                )
                            )
                            serverCount++
                            Log.d("GDFlix", "[$serverCount] Added GoFile")
                        } catch (e: Exception) {
                            Log.e("GDFlix", "GoFile extraction failed: ${e.message}")
                        }
                    }
                    
                    // Telegram File
                    btnLink.contains("t.me", ignoreCase = true) || 
                    btnText.contains("Telegram", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name[Telegram]",
                                    "$name Telegram $title [$size]",
                                    btnLink
                                )
                            )
                            serverCount++
                            Log.d("GDFlix", "[$serverCount] Added Telegram")
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Telegram extraction failed: ${e.message}")
                        }
                    }
                }
            }
            
            Log.d("GDFlix", "Total servers extracted: $serverCount")
        } catch (e: Exception) {
            Log.e("GDFlix", "Error extracting: ${e.message}")
        }
    }
}
