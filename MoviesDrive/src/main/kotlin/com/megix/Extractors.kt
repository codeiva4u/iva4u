package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

// HubCloud Extractor
class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.fit"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Starting extraction for: $url")
            
            // Step 1: Get initial page
            val doc = app.get(url, referer = referer).document
            
            // Step 2: Find the download link generation button
            val downloadLink = doc.selectFirst("a#download")?.attr("href")
            
            if (downloadLink != null) {
                Log.d("HubCloud", "Found download link: $downloadLink")
                
                // Step 3: Navigate to the download link page
                val finalDoc = app.get(downloadLink, referer = url).document
                
                // Step 4: Extract all server links
                val servers = finalDoc.select("a[href*='pixeldrain'], a[href*='pixel'], a[href*='fsl.'], a[href*='mega.co.nz'], a[href*='.zip']")
                
                servers.forEach { server ->
                    val serverUrl = server.attr("href")
                    val serverName = when {
                        serverUrl.contains("pixeldrain", ignoreCase = true) -> "PixelDrain"
                        serverUrl.contains("fsl.", ignoreCase = true) -> "FSL"
                        serverUrl.contains("mega.co.nz", ignoreCase = true) -> "Mega"
                        serverUrl.contains(".zip", ignoreCase = true) -> "ZipDisk"
                        else -> "HubCloud"
                    }
                    
                    if (serverUrl.isNotEmpty() && serverUrl.startsWith("http")) {
                        Log.d("HubCloud", "Adding server: $serverName - $serverUrl")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - $serverName",
                                serverUrl
                            ) {
                                this.referer = downloadLink
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } else {
                Log.e("HubCloud", "Download link not found")
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// GDFlix Extractor
class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDFlix", "Starting extraction for: $url")
            
            // Get the page
            val doc = app.get(url, referer = referer).document
            
            // Extract all download server links
            val servers = doc.select("a[href*='pixeldrain'], a[href*='gdtot'], a[href*='hubcdn'], a[href*='workers.dev']")
            
            if (servers.isEmpty()) {
                // Fallback: try to find any links that look like download servers
                val allLinks = doc.select("a[href]").filter { element ->
                    val href = element.attr("href")
                    href.contains("download", ignoreCase = true) ||
                    href.contains("dl", ignoreCase = true) ||
                    href.contains("file", ignoreCase = true)
                }
                
                allLinks.forEach { link ->
                    val linkUrl = link.attr("href")
                    if (linkUrl.startsWith("http")) {
                        Log.d("GDFlix", "Adding fallback link: $linkUrl")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - Server",
                                linkUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } else {
                servers.forEach { server ->
                    val serverUrl = server.attr("href")
                    val serverName = when {
                        serverUrl.contains("pixeldrain", ignoreCase = true) -> "PixelDrain"
                        serverUrl.contains("gdtot", ignoreCase = true) -> "GDtot"
                        serverUrl.contains("hubcdn", ignoreCase = true) -> "HubCDN"
                        serverUrl.contains("workers.dev", ignoreCase = true) -> "CloudFlare"
                        else -> "GDFlix"
                    }
                    
                    if (serverUrl.isNotEmpty() && serverUrl.startsWith("http")) {
                        Log.d("GDFlix", "Adding server: $serverName - $serverUrl")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - $serverName",
                                serverUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            
            if (servers.isEmpty()) {
                Log.e("GDFlix", "No download servers found")
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}