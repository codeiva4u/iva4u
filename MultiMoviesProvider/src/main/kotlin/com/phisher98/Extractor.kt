package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.UnknownHostException
import java.net.SocketTimeoutException


// GDTOT Extractor - Enhanced error handling for DOWN domains
class GDTOTExtractor : ExtractorApi() {
    override var name = "GDTOT"
    override var mainUrl = "https://gdtot.dad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDTOT", "Attempting extraction from: $url")
            
            // Try to access with short timeout
            val response = app.get(url, referer = referer, timeout = 10L)
            
            if (response.code >= 400) {
                Log.w("GDTOT", "Server returned ${response.code} for $url")
                return
            }
            
            // Add extraction logic here if domains come back online
            Log.i("GDTOT", "Successfully connected but no extraction logic implemented yet")
            
        } catch (e: UnknownHostException) {
            Log.e("GDTOT", "Domain is DOWN or unreachable: ${e.message}")
        } catch (e: SocketTimeoutException) {
            Log.e("GDTOT", "Connection timeout for $url: ${e.message}")
        } catch (e: Exception) {
            Log.e("GDTOT", "Extraction failed for $url: ${e.message}")
        }
    }
}

// StreamHG Extractor - NEW! Replaces FilePress
// Direct download links from multimoviesshg.com in multiple qualities
class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamHG", "Starting extraction from: $url")
            
            // Extract file ID from URL: /f/jw2nbe1cpvk7 or /f/jw2nbe1cpvk7_h
            val fileIdRegex = Regex("/f/([a-zA-Z0-9]+)")
            val fileIdMatch = fileIdRegex.find(url)
            
            if (fileIdMatch == null) {
                Log.e("StreamHG", "Could not extract file ID from URL: $url")
                return
            }
            
            val fileId = fileIdMatch.groupValues[1]
            Log.d("StreamHG", "Extracted file ID: $fileId")
            
            // Define quality variants
            val qualityVariants = listOf(
                Triple("Full HD", "${mainUrl}/f/${fileId}_h", 1080),
                Triple("HD", "${mainUrl}/f/${fileId}_n", 720),
                Triple("Normal", "${mainUrl}/f/${fileId}_l", 480)
            )
            
            var linksFound = 0
            
            qualityVariants.forEach { (qualityLabel, downloadUrl, qualityValue) ->
                try {
                    Log.d("StreamHG", "Checking $qualityLabel quality: $downloadUrl")
                    
                    // Validate link with HEAD request
                    val headResponse = app.head(
                        downloadUrl,
                        referer = "${mainUrl}/f/${fileId}",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ),
                        timeout = 10L
                    )
                    
                    if (headResponse.code == 200 || headResponse.code == 302) {
                        val contentLength = headResponse.headers["Content-Length"]
                        Log.d("StreamHG", "$qualityLabel available (Status: ${headResponse.code}, Size: ${contentLength ?: "unknown"})")
                        
                        callback.invoke(
                            ExtractorLink(
                                "$name - $qualityLabel",
                                "$name - $qualityLabel",
                                downloadUrl,
                                referer = "${mainUrl}/f/${fileId}",
                                quality = qualityValue,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        linksFound++
                    } else {
                        Log.w("StreamHG", "$qualityLabel unavailable (Status: ${headResponse.code})")
                    }
                } catch (e: Exception) {
                    Log.w("StreamHG", "Failed to validate $qualityLabel: ${e.message}")
                }
            }
            
            Log.i("StreamHG", "Successfully extracted $linksFound download links from StreamHG")
            
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}

// Hubcloud Extractor - Enhanced with robust error handling
// Extracts multiple download servers: PixelDrain, 10Gbps, ZipDisk, Telegram
class Hubcloud : ExtractorApi() {
    override var name = "Hubcloud"
    override var mainUrl = "https://hubcloud.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Hubcloud", "Step 1: Loading initial page: $url")
            
            val initialResponse = app.get(url, referer = referer, timeout = 25L)
            
            if (initialResponse.code != 200) {
                Log.w("Hubcloud", "Initial page returned status ${initialResponse.code}")
                return
            }
            
            val initialDoc = initialResponse.document
            
            // Step 2: Get the gamerxyt.com button link
            val gamerxytButton = initialDoc.selectFirst("a#download[href*='gamerxyt.com']")
            if (gamerxytButton == null) {
                Log.e("Hubcloud", "Generate Download Link button not found in page")
                return
            }
            
            val gamerxytUrl = gamerxytButton.attr("href")
            if (gamerxytUrl.isEmpty()) {
                Log.e("Hubcloud", "gamerxyt URL is empty")
                return
            }
            
            Log.d("Hubcloud", "Step 2: Found gamerxyt URL: $gamerxytUrl")
            
            // Step 3: Navigate to gamerxyt page to get all download servers
            val downloadResponse = app.get(gamerxytUrl, referer = url, timeout = 25L)
            
            if (downloadResponse.code != 200) {
                Log.w("Hubcloud", "Download page returned status ${downloadResponse.code}")
                return
            }
            
            val downloadPage = downloadResponse.document
            Log.d("Hubcloud", "Step 3: Loaded download page with servers")
            
            // Extract all download links
            var linksFound = 0
            
            // Server 1: PixelDrain (PixelServer : 2)
            downloadPage.select("a[href*='pixeldrain.dev/api/file']").forEach { element ->
                val pixelUrl = element.attr("href")
                if (pixelUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found PixelDrain: $pixelUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - PixelDrain",
                            "$name - PixelDrain",
                            pixelUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 2: 10Gbps Server (pixel.hubcdn.fans)
            downloadPage.select("a[href*='pixel.hubcdn.fans']").forEach { element ->
                val gbpsUrl = element.attr("href")
                if (gbpsUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found 10Gbps: $gbpsUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - 10Gbps",
                            "$name - 10Gbps",
                            gbpsUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 3: ZipDisk Server (cloudserver workers.dev)
            downloadPage.select("a[href*='cloudserver'][href*='workers.dev']").forEach { element ->
                val zipUrl = element.attr("href")
                if (zipUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found ZipDisk: $zipUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - ZipDisk",
                            "$name - ZipDisk (Extract ZIP)",
                            zipUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 4: Telegram Download
            downloadPage.select("a[href*='telegram'], a[href*='bloggingvector']").forEach { element ->
                val tgUrl = element.attr("href")
                if (tgUrl.isNotEmpty() && tgUrl.startsWith("http")) {
                    Log.d("Hubcloud", "Found Telegram: $tgUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - Telegram",
                            "$name - Telegram",
                            tgUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Bonus: Direct share link (hubcloud.one/drive/ID)
            try {
                val shareLink = downloadPage.selectFirst("input[value*='hubcloud.one/drive/']")
                if (shareLink != null) {
                    val directUrl = shareLink.attr("value")
                    if (directUrl.isNotEmpty()) {
                        Log.d("Hubcloud", "Found direct share: $directUrl")
                        callback.invoke(
                            ExtractorLink(
                                "$name - Direct",
                                "$name - Direct Link",
                                directUrl,
                                gamerxytUrl,
                                Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        linksFound++
                    }
                }
            } catch (e: Exception) {
                Log.w("Hubcloud", "Failed to extract direct share link: ${e.message}")
            }
            
            if (linksFound > 0) {
                Log.i("Hubcloud", "Step 4: Successfully extracted $linksFound download links")
            } else {
                Log.w("Hubcloud", "No download links were extracted")
            }
            
        } catch (e: UnknownHostException) {
            Log.e("Hubcloud", "Domain unreachable: ${e.message}")
        } catch (e: SocketTimeoutException) {
            Log.e("Hubcloud", "Connection timeout: ${e.message}")
        } catch (e: Exception) {
            Log.e("Hubcloud", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
