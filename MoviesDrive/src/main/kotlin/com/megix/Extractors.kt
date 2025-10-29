package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

// HubCloud Extractors
class PixelDrainHubCloud : ExtractorApi() {
    override val name = "PixelDrain [HubCloud]"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern: hubcloud.one/drive/{id}
            val hubCloudIdRegex = """hubcloud\.(?:one|fit|club)/drive/([a-zA-Z0-9]+)""".toRegex()
            val hubCloudMatch = hubCloudIdRegex.find(url)
            
            if (hubCloudMatch != null) {
                val hubCloudId = hubCloudMatch.groupValues[1]
                
                // Step 1: Get the hubcloud page to extract the gamerxyt link
                val hubCloudPage = app.get(url).document
                val downloadLink = hubCloudPage.selectFirst("a#download")?.attr("href")
                
                if (downloadLink != null && downloadLink.contains("gamerxyt.com")) {
                    // Step 2: Follow the gamerxyt redirect
                    val gamerResponse = app.get(downloadLink, allowRedirects = true)
                    val finalUrl = gamerResponse.url
                    
                    // Pattern: pixeldrain.dev/u/{file_id}
                    val pixelDrainRegex = """pixeldrain\.dev/u/([a-zA-Z0-9_-]+)""".toRegex()
                    val pixelMatch = pixelDrainRegex.find(finalUrl)
                    
                    if (pixelMatch != null) {
                        val fileId = pixelMatch.groupValues[1]
                        val directUrl = "https://pixeldrain.dev/api/file/$fileId?download"
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                url = directUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail - continue with other extractors
        }
    }
}

class HubCloud10Gbps : ExtractorApi() {
    override val name = "Server:10Gbps [HubCloud]"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hubCloudIdRegex = """hubcloud\.(?:one|fit|club)/drive/([a-zA-Z0-9]+)""".toRegex()
            val match = hubCloudIdRegex.find(url)
            
            if (match != null) {
                val id = match.groupValues[1]
                val hubCloudPage = app.get(url).document
                val downloadLink = hubCloudPage.selectFirst("a#download")?.attr("href")
                
                if (downloadLink != null) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = downloadLink,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
}

class FSLServer : ExtractorApi() {
    override val name = "FSL:Server [HubCloud]"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hubCloudIdRegex = """hubcloud\.(?:one|fit|club)/drive/([a-zA-Z0-9]+)""".toRegex()
            val match = hubCloudIdRegex.find(url)
            
            if (match != null) {
                val hubCloudPage = app.get(url).document
                val downloadLink = hubCloudPage.selectFirst("a#download")?.attr("href")
                
                if (downloadLink != null && downloadLink.contains("gamerxyt")) {
                    val response = app.get(downloadLink, allowRedirects = true)
                    val finalUrl = response.url
                    
                    if (finalUrl.contains("pixeldrain")) {
                        val fileIdRegex = """pixeldrain\.dev/(?:u|api/file)/([a-zA-Z0-9_-]+)""".toRegex()
                        val fileMatch = fileIdRegex.find(finalUrl)
                        
                        if (fileMatch != null) {
                            val fileId = fileMatch.groupValues[1]
                            val directUrl = "https://pixeldrain.dev/api/file/$fileId?download"
                            
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    this.name,
                                    url = directUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer ?: ""
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
}

class MegaServer : ExtractorApi() {
    override val name = "Mega:Server [HubCloud]"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hubCloudIdRegex = """hubcloud\.(?:one|fit|club)/drive/([a-zA-Z0-9]+)""".toRegex()
            val match = hubCloudIdRegex.find(url)
            
            if (match != null) {
                val hubCloudPage = app.get(url).document
                val downloadLink = hubCloudPage.selectFirst("a#download")?.attr("href")
                
                if (downloadLink != null) {
                    val response = app.get(downloadLink, allowRedirects = true)
                    
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = response.url,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
}

// GDFlix Extractors
class InstantDL10GBPS : ExtractorApi() {
    override val name = "Instant DL [10GBPS]"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern: gdflix.dev/file/{id} or gdlink.*/file/{id}
            val gdflixIdRegex = """(?:gdflix|gdlink)\.[a-z]+/file/([a-zA-Z0-9]+)""".toRegex()
            val match = gdflixIdRegex.find(url)
            
            if (match != null) {
                val gdflixPage = app.get(url).document
                
                // Find Instant DL button with busycdn.cfd domain
                val instantDlLink = gdflixPage.select("a").find { element ->
                    element.text().contains("Instant DL", ignoreCase = true) &&
                    element.attr("href").contains("busycdn.cfd")
                }?.attr("href")
                
                if (instantDlLink != null && instantDlLink.isNotEmpty()) {
                    // Extract the actual download URL
                    val instantRegex = """instant\.busycdn\.cfd/([a-zA-Z0-9]+)::""".toRegex()
                    val instantMatch = instantRegex.find(instantDlLink)
                    
                    if (instantMatch != null) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                url = instantDlLink,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
}

class PixelDrainGDFlix : ExtractorApi() {
    override val name = "PixelDrain DL [20MB/s]"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Pattern: gdflix.dev/file/{id}
            val gdflixIdRegex = """(?:gdflix|gdlink)\.[a-z]+/file/([a-zA-Z0-9]+)""".toRegex()
            val match = gdflixIdRegex.find(url)
            
            if (match != null) {
                val gdflixPage = app.get(url).document
                
                // Find PixelDrain DL button
                val pixelDrainLink = gdflixPage.select("a").find { element ->
                    element.text().contains("PixelDrain", ignoreCase = true) &&
                    element.attr("href").contains("pixeldrain.dev")
                }?.attr("href")
                
                if (pixelDrainLink != null && pixelDrainLink.isNotEmpty()) {
                    // Pattern: pixeldrain.dev/api/file/{id}?download
                    val fileIdRegex = """pixeldrain\.dev/api/file/([a-zA-Z0-9_-]+)""".toRegex()
                    val fileMatch = fileIdRegex.find(pixelDrainLink)
                    
                    if (fileMatch != null) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                url = pixelDrainLink,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
}