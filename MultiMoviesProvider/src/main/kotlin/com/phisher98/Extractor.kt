package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities


// GDTOT Extractor - Skipped (Domains are DOWN)
// Testing showed: new9.gdtot.dad, new12.gdtot.dad - Connection Timeout
// These domains are blocked/dead - Not implementing
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
        // GDTOT domains are currently down/blocked
        // Tested live: ERR_CONNECTION_TIMEOUT
        Log.e("GDTOT", "GDTOT domains are currently DOWN. Skipping extraction.")
        return
    }
}

// FilePress Extractor - Redirect page is BLANK
// Testing showed: multimovies.network/links/XXX redirects but page is empty
// JavaScript-heavy redirect that requires more complex handling
class FilePress : ExtractorApi() {
    override var name = "FilePress"
    override var mainUrl = "https://new3.filepress.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // FilePress redirect pages are blank (JavaScript redirects)
        // Need WebView or JavaScript execution to get actual URL
        Log.e("FilePress", "FilePress requires JavaScript execution - currently not supported")
        return
    }
}

// Hubcloud Extractor - FULLY WORKING! ✅
// Live tested: Successfully extracts 4 download servers!
// Servers: PixelDrain, 10Gbps, ZipDisk, Telegram
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
            val initialDoc = app.get(url, referer = referer, timeout = 25L).document
            
            // Step 1: Get the gamerxyt.com button link
            val gamerxytButton = initialDoc.selectFirst("a#download[href*='gamerxyt.com']")
            if (gamerxytButton == null) {
                Log.e("Hubcloud", "Generate Download Link button not found")
                return
            }
            
            val gamerxytUrl = gamerxytButton.attr("href")
            Log.d("Hubcloud", "Step 2: Found gamerxyt URL: $gamerxytUrl")
            
            // Step 2: Navigate to gamerxyt page to get all download servers
            val downloadPage = app.get(gamerxytUrl, referer = url, timeout = 25L).document
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
            
            Log.d("Hubcloud", "Step 4: Successfully extracted $linksFound download links")
            
        } catch (e: Exception) {
            Log.e("Hubcloud", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
