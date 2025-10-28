package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

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
                            // Add download link with proper API endpoint
                            val finalUrl = if (url.contains("?download")) {
                                url
                            } else {
                                "$apiBase/api/file/$mId?download"
                            }
                            
                            callback.invoke(
                                newExtractorLink(
                                    this.name, 
                                    "${this.name} $fileName [$formattedSize]", 
                                    finalUrl
                                ) {
                                    this.referer = "$apiBase/"
                                    this.quality = getIndexQuality(fileName)
                                }
                            )
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

            var link = if (url.contains("drive")) {
                // Extract direct download link from button
                val downloadBtn = doc.selectFirst("a#download[href*=gamerxyt]") 
                    ?: doc.selectFirst("a[href*=download]")
                downloadBtn?.attr("href") ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            // If link is relative, make it absolute
            if (link.isNotEmpty() && link.startsWith("/")) {
                link = getBaseUrl(url) + link
            }
            
            // If we found intermediate link (gamerxyt), follow it
            if (link.contains("gamerxyt")) {
                try {
                    val redirectResponse = app.get(link, allowRedirects = true)
                    val finalLink = redirectResponse.url
                    if (finalLink.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "$name[Direct]",
                                "$name Direct $header[$size]",
                                finalLink
                            ) {
                                this.quality = getIndexQuality(header)
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("HubCloud", "Error following gamerxyt link: ${e.message}")
                }
            }

            // Also check for alternative download buttons on same page
            val div = doc.selectFirst("div.card-body")
            div?.select("h2 a.btn, a.btn")?.forEach {
                val btnLink = it.attr("href")
                val text = it.text()

                when {
                    // Prioritize 10Gbps server - FASTEST
                    text.contains("Download [Server : 10Gbps]", ignoreCase = true) || 
                    text.contains("10Gbps", ignoreCase = true) -> {
                        try {
                            // Follow the redirect chain to get final download link
                            val redirectResponse = app.get(btnLink, allowRedirects = false)
                            val locationHeader = redirectResponse.headers["location"] ?: return@forEach
                            
                            val dlink = if (locationHeader.contains("link=")) {
                                locationHeader.substringAfter("link=")
                            } else {
                                locationHeader
                            }
                            
                            // Validate final link
                            if (dlink.isNotBlank() && !dlink.contains("null", ignoreCase = true)) {
                                callback.invoke(
                                    newExtractorLink("$name[10Gbps⚡]", "$name 10Gbps Fast $header[$size]", dlink) {
                                        this.quality = getIndexQuality(header)
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "Error processing 10Gbps link: ${e.message}")
                        }
                    }
                    
                    text.contains("Download [FSL Server]", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink("$name[FSL]", "$name FSL $header[$size]", btnLink) {
                                quality = getIndexQuality(header)
                            }
                        )
                    }

                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(name, "$name $header[$size]", btnLink) {
                                quality = getIndexQuality(header)
                            }
                        )
                    }

                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                                .headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[Buzz]",
                                        "$name Buzz $header[$size]",
                                        getBaseUrl(btnLink) + dlink
                                    ) {
                                        quality = getIndexQuality(header)
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "BuzzServer error: ${e.message}")
                        }
                    }

                    btnLink.contains("pixeldra", ignoreCase = true) -> {
                        try {
                            PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("HubCloud", "Pixeldrain extraction failed: ${e.message}")
                        }
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

open class fastdlserver : ExtractorApi() {
    override val name: String = "fastdlserver"
    override var mainUrl = "https://pixel.hubcdn.fans/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

open class Gofile : ExtractorApi() {
    override val name: String = "Gofile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val contentId = url.substringAfterLast("/")
            val apiUrl = "$mainUrl/api/getContent?contentId=$contentId"
            
            val response = app.get(apiUrl).parsedSafe<GofileResponse>()
            response?.data?.contents?.values?.forEach { file ->
                if (file.mimetype?.startsWith("video/", ignoreCase = true) == true) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name ${file.name}",
                            file.link ?: return@forEach
                        ) {
                            this.quality = getIndexQuality(file.name ?: "")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Gofile", "Error extracting: ${e.message}")
        }
    }

    data class GofileResponse(
        val status: String?,
        val data: GofileData?
    )

    data class GofileData(
        val contents: Map<String, GofileContent>?
    )

    data class GofileContent(
        val name: String?,
        val mimetype: String?,
        val link: String?
    )
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
            
            // Find all download buttons  
            val downloadButtons = document.select("a.btn, a[class*=btn]")
            
            downloadButtons.forEach { button ->
                val btnText = button.text()
                val btnLink = button.attr("href")
                
                // Skip empty or invalid links
                if (btnLink.isBlank() || btnLink == "#") return@forEach
                
                when {
                    // Instant DL - Direct fast link (busycdn/instant)
                    btnText.contains("Instant", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name [Instant⚡]",
                                    "$name Instant DL $title [$size]",
                                    btnLink
                                ) {
                                    this.quality = getIndexQuality(title)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Instant DL error: ${e.message}")
                        }
                    }
                    // 10Gbps Login Server
                    btnText.contains("Login To DL", ignoreCase = true) && btnText.contains("10GBPS", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name [10Gbps]",
                                    "$name 10Gbps DL $title [$size]",
                                    btnLink
                                ) {
                                    this.quality = getIndexQuality(title)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "10Gbps DL error: ${e.message}")
                        }
                    }
                    // PixelDrain - Already working extractor
                    btnLink.contains("pixeldra", ignoreCase = true) -> {
                        try {
                            PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Pixeldrain extraction failed: ${e.message}")
                        }
                    }
                    // Fast Cloud/ZipDisk - GDFlix internal servers
                    btnText.contains("FAST CLOUD", ignoreCase = true) || 
                    btnText.contains("ZIPDISK", ignoreCase = true) ||
                    btnLink.contains("/zfile/", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name [Cloud]",
                                    "$name Cloud DL $title [$size]",
                                    btnLink
                                ) {
                                    this.quality = getIndexQuality(title)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Cloud DL error: ${e.message}")
                        }
                    }
                    // GoFile Mirror
                    btnLink.contains("goflix", ignoreCase = true) || 
                    btnLink.contains("gofile", ignoreCase = true) -> {
                        try {
                            // Use Gofile extractor if available, otherwise add direct link
                            if (btnText.contains("GoFile", ignoreCase = true)) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name [GoFile]",
                                        "$name GoFile $title [$size]",
                                        btnLink
                                    ) {
                                        this.quality = getIndexQuality(title)
                                    }
                                )
                            } else {
                                loadExtractor(btnLink, url, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "GoFile extraction failed: ${e.message}")
                        }
                    }
                    // Telegram File
                    btnLink.contains("filesgram", ignoreCase = true) || 
                    btnText.contains("Telegram", ignoreCase = true) -> {
                        try {
                            callback.invoke(
                                newExtractorLink(
                                    "$name [Telegram]",
                                    "$name Telegram $title [$size]",
                                    btnLink
                                ) {
                                    this.quality = getIndexQuality(title)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Telegram extraction failed: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error extracting: ${e.message}")
        }
    }
}
