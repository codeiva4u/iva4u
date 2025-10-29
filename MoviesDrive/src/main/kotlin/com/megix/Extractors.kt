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

// Generic GDFlix Extractor (handles Instant DL 10GBPS, PixelDrain DL 20MB/s, etc.)
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
            Log.d("GDFlix", "Processing URL: $url")
            
            // Fetch GDFlix page
            val document = app.get(url).document
            
            // Extract file info from page title or heading
            val fileTitle = document.selectFirst("h1.text-2xl, h2.text-xl")?.text() 
                ?: document.title()
            
            // Extract quality from title (480p, 720p, 1080p, etc.)
            val quality = Regex("""(\d{3,4})[pP]""").find(fileTitle)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Qualities.Unknown.value
            
            val labelExtras = if (fileTitle.isNotEmpty()) "[$fileTitle]" else ""
            
            // Process all download links/buttons
            document.select("a[href], button[onclick]").forEach { element ->
                val href = element.attr("href").ifEmpty { 
                    // Extract URL from onclick if present
                    element.attr("onclick").let {
                        Regex("""(?:window\.location|location\.href)\s*=\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1)
                    } ?: ""
                }
                val text = element.text()
                
                // Skip empty or invalid links
                if (href.isBlank() || href.startsWith("javascript:") || 
                    text.contains("Back", ignoreCase = true) ||
                    text.contains("Home", ignoreCase = true)) {
                    return@forEach
                }
                
                when {
                    // Instant DL 10GBPS
                    (href.contains("instant.busycdn.cfd") || text.contains("Instant", ignoreCase = true)) &&
                    (text.contains("10GBPS", ignoreCase = true) || text.contains("10 GBPS", ignoreCase = true)) -> {
                        try {
                            val response = app.get(href, allowRedirects = true)
                            val finalUrl = response.url
                            
                            callback.invoke(
                                newExtractorLink(
                                    "GDFlix",
                                    "Instant DL 10GBPS $labelExtras",
                                    finalUrl
                                ) {
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Instant DL error: ${e.message}")
                        }
                    }
                    
                    // PixelDrain DL 20MB/s
                    href.contains("pixeldrain") || text.contains("PixelDrain", ignoreCase = true) -> {
                        try {
                            val fileId = Regex("""pixeldrain\.(?:dev|com)/(?:api/file/|u/)?([a-zA-Z0-9]+)""").find(href)?.groupValues?.get(1)
                            
                            if (fileId != null) {
                                val downloadUrl = "https://pixeldrain.com/api/file/$fileId?download"
                                
                                callback.invoke(
                                    newExtractorLink(
                                        "PixelDrain",
                                        "PixelDrain DL 20MB/s $labelExtras",
                                        downloadUrl
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "PixelDrain error: ${e.message}")
                        }
                    }
                    
                    // FastCDN / Cloud Download (R2)
                    href.contains("fastcdn-dl.pages.dev") -> {
                        try {
                            val actualUrl = Regex("""url=([^&]+)""").find(href)?.groupValues?.get(1)
                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            
                            if (actualUrl != null && actualUrl.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        "GDFlix",
                                        "Cloud Download [R2] $labelExtras",
                                        actualUrl
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "FastCDN error: ${e.message}")
                        }
                    }
                    
                    // Direct R2 CloudFlare links
                    href.contains("r2.dev") && href.contains("pub-") -> {
                        callback.invoke(
                            newExtractorLink(
                                "CloudFlare R2",
                                "CloudFlare R2 Storage $labelExtras",
                                href
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                    
                    // ZipDisk / Fast Cloud
                    href.contains("zfile") || text.contains("ZIPDISK", ignoreCase = true) || 
                    text.contains("Fast Cloud", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "GDFlix",
                                "Fast Cloud/ZipDisk $labelExtras",
                                href
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                    
                    // GoFile Mirror
                    href.contains("goflix.sbs") || text.contains("GoFile", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "GoFile",
                                "GoFile Mirror $labelExtras",
                                href
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                    
                    // Telegram File
                    href.contains("filesgram.site") || href.contains("t.me") && text.contains("Telegram", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Telegram",
                                "Telegram File $labelExtras",
                                href
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                    
                    // Login to DL (needs authentication - show but may not work)
                    href.contains("/login") && text.contains("Login", ignoreCase = true) -> {
                        Log.d("GDFlix", "Login required for: $text - Skipping")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error: ${e.message}")
        }
    }
}
