package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log

// HubCloud Extractor - Handles PixelServer:2, Server:10Gbps, FSL:Server, Mega:Server
open class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Processing URL: $url")
            
            // Get base URL for relative path handling
            val baseUrl = try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                mainUrl
            }
            
            // Handle hubcloud.php direct links or extract from page
            val downloadPageUrl = if ("hubcloud.php" in url) {
                url
            } else {
                val doc = app.get(url).document
                val href = doc.selectFirst("a#download, a[href*='hubcloud.php']")?.attr("href") ?: ""
                if (href.startsWith("http", ignoreCase = true)) {
                    href
                } else if (href.isNotEmpty()) {
                    baseUrl.trimEnd('/') + "/" + href.trimStart('/')
                } else {
                    Log.e("HubCloud", "No download link found")
                    return
                }
            }
            
            if (downloadPageUrl.isEmpty()) return
            
            // Fetch the download page with all server buttons
            val document = app.get(downloadPageUrl).document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()
            
            // Extract quality from header (480p, 720p, 1080p, etc.)
            val quality = Regex("""(\d{3,4})[pP]""").find(header)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Qualities.Unknown.value
            
            val labelExtras = buildString {
                if (header.isNotEmpty()) append("[$header]")
                if (size.isNotEmpty()) append(" [$size]")
            }
            
            // Process all server buttons
            document.select("a.btn, a.btn-success, a.btn-danger, a.btn-primary").forEach { element ->
                val link = element.attr("href")
                val text = element.text()
                
                // Skip empty links or non-download links
                if (link.isBlank() || text.contains("Copy", ignoreCase = true) || 
                    text.contains("Logout", ignoreCase = true) ||
                    text.contains("Login", ignoreCase = true)) {
                    return@forEach
                }
                
                when {
                    // PixelServer:2 (PixelDrain)
                    text.contains("PixelServer", ignoreCase = true) || 
                    text.contains("Pixeldrain", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "PixelDrain",
                                "PixelServer:2 $labelExtras",
                                link
                            ) { 
                                this.quality = quality 
                            }
                        )
                    }
                    
                    // Server:10Gbps
                    text.contains("10Gbps", ignoreCase = true) || 
                    text.contains("10 Gbps", ignoreCase = true) -> {
                        try {
                            var currentLink = link
                            var redirectUrl: String? = null
                            
                            // Follow redirects until we get link= parameter
                            var attempts = 0
                            while (attempts < 5) {
                                val response = app.get(currentLink, allowRedirects = false)
                                redirectUrl = response.headers["location"]
                                if (redirectUrl == null) break
                                if ("link=" in redirectUrl) break
                                currentLink = redirectUrl
                                attempts++
                            }
                            
                            val finalLink = redirectUrl?.substringAfter("link=")
                            if (finalLink != null && finalLink.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        "HubCloud",
                                        "Server:10Gbps $labelExtras",
                                        finalLink
                                    ) { 
                                        this.quality = quality 
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "10Gbps error: ${e.message}")
                        }
                    }
                    
                    // FSL:Server (Fast Server Link)
                    text.contains("FSL", ignoreCase = true) || 
                    text.contains("Fast Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud",
                                "FSL:Server $labelExtras",
                                link
                            ) { 
                                this.quality = quality 
                            }
                        )
                    }
                    
                    // Mega:Server
                    text.contains("Mega", ignoreCase = true) && 
                    text.contains("Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud",
                                "Mega:Server $labelExtras",
                                link
                            ) { 
                                this.quality = quality 
                            }
                        )
                    }
                    
                    // Generic download buttons
                    text.contains("Download", ignoreCase = true) || 
                    text.contains("Direct", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud",
                                "HubCloud $labelExtras",
                                link
                            ) { 
                                this.quality = quality 
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Error: ${e.message}")
        }
    }
}

// GDFlix Instant DL Extractor
open class GDFlixInstantExtractor : ExtractorApi() {
    override val name = "GDFlix Instant"
    override val mainUrl = "https://instant.busycdn.cfd"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern for instant download: https://instant.busycdn.cfd/{encrypted_data}
            if (url.contains("instant.busycdn.cfd")) {
                val response = app.get(url, allowRedirects = true)
                val finalUrl = response.url
                
                // Extract filename if available from headers or URL
                val contentDisposition = response.headers["content-disposition"]
                val fileName = contentDisposition?.let {
                    Regex("""filename[*]?=['"]?([^'";]+)""").find(it)?.groupValues?.get(1)
                } ?: "GDFlix_Instant.mp4"
                
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "GDFlix Instant - 10GBPS",
                        finalUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer ?: ""
                    }
                )
            }
        } catch (e: Exception) {
            // Log error but continue
        }
    }
}

// PixelDrain Extractor for GDFlix
open class PixelDrainExtractor : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern: https://pixeldrain.dev/api/file/{id}?download or https://pixeldrain.dev/api/file/{id}
            val fileId = Regex("""pixeldrain\.dev/(?:api/file/)?([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            
            if (fileId != null) {
                // Construct proper download URL
                val downloadUrl = "https://pixeldrain.dev/api/file/$fileId?download"
                
                // Get file info
                val response = app.get(downloadUrl, allowRedirects = false)
                val finalUrl = if (response.code in 300..399) {
                    response.headers["location"] ?: downloadUrl
                } else {
                    downloadUrl
                }
                
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "PixelDrain - 20MB/s",
                        finalUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer ?: ""
                    }
                )
            }
        } catch (e: Exception) {
            // Log error but continue
        }
    }
}

// FastCDN / Cloud Download Extractor
open class FastCDNExtractor : ExtractorApi() {
    override val name = "FastCDN"
    override val mainUrl = "https://fastcdn-dl.pages.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern: https://fastcdn-dl.pages.dev/?url={encoded_url}
            if (url.contains("fastcdn-dl.pages.dev")) {
                // Extract actual URL from query parameter
                val actualUrl = Regex("""url=([^&]+)""").find(url)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                
                if (actualUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "FastCDN - Cloud Download",
                            actualUrl
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = referer ?: ""
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Log error but continue
        }
    }
}

// Generic GDFlix Extractor (handles all GDFlix servers)
open class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Fetch GDFlix page
            val response = app.get(url).document
            
            // Extract all download links
            val downloadLinks = response.select("a[href]")
            
            downloadLinks.forEach { element ->
                val href = element.attr("href")
                val text = element.text()
                
                when {
                    // Instant DL 10GBPS
                    href.contains("instant.busycdn.cfd") && text.contains("Instant", ignoreCase = true) -> {
                        GDFlixInstantExtractor().getUrl(href, referer, subtitleCallback, callback)
                    }
                    // PixelDrain DL
                    href.contains("pixeldrain") && text.contains("PixelDrain", ignoreCase = true) -> {
                        PixelDrainExtractor().getUrl(href, referer, subtitleCallback, callback)
                    }
                    // FastCDN / Cloud Download
                    href.contains("fastcdn-dl.pages.dev") || href.contains("r2.dev") -> {
                        if (href.contains("fastcdn-dl")) {
                            FastCDNExtractor().getUrl(href, referer, subtitleCallback, callback)
                        } else {
                            // Direct R2 link
                            callback.invoke(
                                newExtractorLink(
                                    "CloudFlare R2",
                                    "CloudFlare R2 Storage",
                                    href
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = referer ?: ""
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but continue
        }
    }
}
