package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
// HubCloud Extractors
class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("hubcloud.one", "hubcloud.fit", "hubcloud.fans")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Follow redirect chain through gamerxyt.com
            val doc = app.get(url).document
            
            // Find the download link generator
            val downloadGenLink = doc.selectFirst("a:contains(Generate Direct Download Link)")?.attr("href")
            
            if (downloadGenLink != null) {
                // Follow to intermediate page
                val intermediatePage = app.get(downloadGenLink).document
                
                // Extract pixeldrain or other embeds
                val iframes = intermediatePage.select("iframe[src*=pixeldrain]")                
                iframes.forEach { iframe ->
                    val embedUrl = iframe.attr("src")
                    if (embedUrl.contains("pixeldrain")) {
                        val fileId = embedUrl.substringAfter("/u/").substringBefore("?")
                        val directUrl = "https://pixeldrain.dev/api/file/$fileId?download"
                        callback(
                            newExtractorLink(
                                "HubCloud - PixelServer",
                                "HubCloud - PixelServer",
                                directUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                
                // Also check for direct links in page
                val directLinks = intermediatePage.select("a[href*=pixeldrain]")                
                directLinks.forEach { link ->
                    val linkUrl = link.attr("href")
                    if (linkUrl.contains("pixeldrain.dev/u/")) {
                        val fileId = linkUrl.substringAfter("/u/").substringBefore("?")
                        val directUrl = "https://pixeldrain.dev/api/file/$fileId?download"
                        callback(
                            newExtractorLink(
                                "HubCloud - PixelDrain",
                                "HubCloud - PixelDrain",
                                directUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

class PixelDrainExtractor : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("pixeldrain.dev", "pixeldrain.com")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = if (url.contains("/u/")) {
                url.substringAfter("/u/").substringBefore("?")
            } else if (url.contains("/api/file/")) {
                url.substringAfter("/api/file/").substringBefore("?")
            } else {
                return
            }
            
            val directUrl = "https://pixeldrain.dev/api/file/$fileId?download"
            callback(
                newExtractorLink(
                    name,
                    name,
                    directUrl
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

class InstantDLExtractor : ExtractorApi() {
    override val name = "Instant DL"
    override val mainUrl = "https://instant.busycdn.cfd"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("busycdn.cfd", "instant.busycdn.cfd")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Direct download link from busycdn.cfd
            // BusyCDN links often redirect to final signed file URLs; follow once
            val resp = app.get(url, allowRedirects = true)
            val finalUrl = resp.url
            callback(
                newExtractorLink(
                    name,
                    "Instant DL 10GBPS",
                    finalUrl
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    if (finalUrl.contains(".m3u8")) {
                        this.type = ExtractorLinkType.M3U8
                    }
                }
            )
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

// GDFlix Extractors
class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("gdflix.dev", "new7.gdflix.net", "gdflix.net")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            
            // Extract Instant DL 10GBPS link
            val instantDLLink = doc.selectFirst("a:contains(Instant DL)")?.attr("href")
            if (instantDLLink != null && instantDLLink.contains("busycdn.cfd")) {
                val resp = app.get(instantDLLink, allowRedirects = true)
                val finalUrl = resp.url
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix - Instant 10GBPS",
                        finalUrl
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        if (finalUrl.contains(".m3u8")) {
                            this.type = ExtractorLinkType.M3U8
                        }
                    }
                )
            }
            
            // Extract PixelDrain DL link
            val pixelDrainLink = doc.selectFirst("a:contains(PixelDrain DL)")?.attr("href")
            if (pixelDrainLink != null && pixelDrainLink.contains("pixeldrain.dev")) {
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix - PixelDrain 20MB/s",
                        pixelDrainLink
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Extract Fast Cloud/ZipDisk link
            val fastCloudLink = doc.selectFirst("a:contains(FAST CLOUD)")?.attr("href")
            if (fastCloudLink != null) {
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix - FastCloud/ZipDisk",
                        fastCloudLink
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        if (fastCloudLink.contains(".m3u8")) {
                            this.type = ExtractorLinkType.M3U8
                        }
                    }
                )
            }
            
            // Extract Cloudflare R2 link
            val r2Link = doc.selectFirst("a:contains(CLOUD DOWNLOAD)")?.attr("href")
            if (r2Link != null) {
                // Extract actual R2 URL from fastcdn-dl redirect
                val r2Url = java.net.URLDecoder.decode(r2Link.substringAfter("url="), "UTF-8")
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix - Cloud R2",
                        r2Url
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        if (r2Url.contains(".m3u8")) {
                            this.type = ExtractorLinkType.M3U8
                        }
                    }
                )
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

class FSLServerExtractor : ExtractorApi() {
    override val name = "FSL Server"
    override val mainUrl = "https://new7.gdflix.net"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("gdflix.net", "new7.gdflix.net")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Handle zipdisk/fast cloud links
            callback(
                newExtractorLink(
                    name,
                    "FSL Server - FastCloud",
                    url
                ) {
                    this.referer = referer ?: ""
                    this.quality = Qualities.Unknown.value
                    if (url.contains(".m3u8")) {
                        this.type = ExtractorLinkType.M3U8
                    }
                }
            )
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

class MegaServerExtractor : ExtractorApi() {
    override val name = "Mega Server"
    override val mainUrl = "https://new7.gdflix.net"
    override val requiresReferer = false
    
    companion object {
        val domains = listOf("gdflix.net", "new7.gdflix.net", "goflix.sbs")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Handle additional mirrors
            callback(
                newExtractorLink(
                    name,
                    "Mega Server",
                    url
                ) {
                    this.referer = referer ?: ""
                    this.quality = Qualities.Unknown.value
                    if (url.contains(".m3u8")) {
                        this.type = ExtractorLinkType.M3U8
                    }
                }
            )
        } catch (e: Exception) {
            // Silently fail
        }
    }
}