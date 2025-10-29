package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.Qualities

// HubCloud Extractor - PixelServer:2, Server:10Gbps, FSL:Server, Mega:Server
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
            // Extract file ID from HubCloud URL
            val fileId = Regex("""hubcloud\.[a-z]+/drive/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            
            if (fileId != null) {
                // Fetch the HubCloud page
                val response = app.get(url).document
                
                // Extract download link - pattern: https://gamerxyt.com/hubcloud.php?host=hubcloud&id={id}&token={token}
                val downloadLink = response.selectFirst("a#download, a[href*='gamerxyt.com/hubcloud.php']")?.attr("href")
                    ?: response.selectFirst("a[href*='hubcloud.php']")?.attr("href")
                
                if (downloadLink != null && downloadLink.contains("gamerxyt")) {
                    // Follow the download link to get final URL
                    val finalResponse = app.get(downloadLink, allowRedirects = true)
                    val finalUrl = finalResponse.url
                    
                    // Extract filename from response
                    val fileName = response.selectFirst(".card-header")?.text() 
                        ?: "HubCloud_${fileId}.mp4"
                    
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "HubCloud - $fileName",
                            finalUrl
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = mainUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Log error but continue
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
