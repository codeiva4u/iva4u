package com.hdhub4u

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// Utility function to get base URL
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        ""
    }
}

/**
 * HubDrive Extractor
 * Handles: hubdrive.space/file/{id}
 * Redirects to HubCloud for actual download
 */
class HubDrive : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubDrive"
        Log.d(tag, "Processing HubDrive URL: $url")

        try {
            val document = app.get(url, referer = referer).document
            
            // Find HubCloud link - the main download button
            val hubcloudLink = document.selectFirst("a[href*=hubcloud]")?.attr("href")
                ?: document.selectFirst("a[href*=gamerxyt]")?.attr("href")
                ?: document.selectFirst("a.btn[href]")?.attr("href")
            
            if (!hubcloudLink.isNullOrBlank()) {
                Log.d(tag, "Found HubCloud link: $hubcloudLink")
                HubCloud().getUrl(hubcloudLink, referer ?: url, subtitleCallback, callback)
            } else {
                Log.e(tag, "No HubCloud link found on page")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * GadgetsWeb Extractor
 * Handles: gadgetsweb.xyz/?id={base64_encoded_id}
 * Decodes Base64 ID and follows to HubCloud
 */
class GadgetsWeb : ExtractorApi() {
    override val name = "GadgetsWeb"
    override val mainUrl = "https://gadgetsweb.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GadgetsWeb"
        Log.d(tag, "Processing GadgetsWeb URL: $url")

        try {
            // GadgetsWeb redirects to HubCloud - follow the redirect
            val response = app.get(url, referer = referer, allowRedirects = false)
            val locationHeader = response.headers["location"]
            
            if (!locationHeader.isNullOrBlank()) {
                Log.d(tag, "Redirect to: $locationHeader")
                when {
                    locationHeader.contains("hubcloud", ignoreCase = true) ||
                    locationHeader.contains("gamerxyt", ignoreCase = true) -> {
                        HubCloud().getUrl(locationHeader, referer ?: url, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(locationHeader, referer ?: mainUrl, subtitleCallback, callback)
                    }
                }
                return
            }
            
            // If no redirect, try to find links in page
            val document = app.get(url, referer = referer).document
            val hubcloudLink = document.selectFirst("a[href*=hubcloud], a[href*=gamerxyt]")?.attr("href")
            
            if (!hubcloudLink.isNullOrBlank()) {
                Log.d(tag, "Found HubCloud link: $hubcloudLink")
                HubCloud().getUrl(hubcloudLink, referer ?: url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HDStream4u Extractor
 * Handles: hdstream4u.com/file/{id}
 * Extracts M3U8 streaming sources
 */
class HDStream4u : ExtractorApi() {
    override val name = "HDStream4u"
    override val mainUrl = "https://hdstream4u.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HDStream4u"
        Log.d(tag, "Processing HDStream4u URL: $url")

        try {
            val response = app.get(url, referer = referer ?: mainUrl)
            val html = response.text
            val baseUrl = getBaseUrl(url)

            // Regex patterns to find video sources
            val m3u8Patterns = listOf(
                Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""source\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            )

            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues.getOrNull(1) ?: match.value
                    Log.d(tag, "Found M3U8: $m3u8Url")
                    
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        baseUrl,
                        headers = mapOf("Referer" to "$baseUrl/")
                    ).forEach(callback)
                    return
                }
            }

            // Try iframe extraction
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            
            if (!iframeSrc.isNullOrBlank()) {
                Log.d(tag, "Found iframe: $iframeSrc")
                loadExtractor(iframeSrc, url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HubStream Extractor
 * Handles: hubstream.art/{hash}
 * Extracts video stream sources with packed JS support
 */
class HubStream : ExtractorApi() {
    override val name = "HubStream"
    override val mainUrl = "https://hubstream.art"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubStream"
        Log.d(tag, "Processing HubStream URL: $url")

        try {
            val response = app.get(url, referer = referer ?: mainUrl)
            val html = response.text
            val baseUrl = getBaseUrl(url)

            // Check for packed JavaScript
            if (!getPacked(html).isNullOrEmpty()) {
                val unpacked = getAndUnpack(html)
                Log.d(tag, "Unpacked JS found")
                
                val m3u8Match = Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""").find(unpacked)
                if (m3u8Match != null) {
                    val m3u8Url = m3u8Match.groupValues[1]
                    Log.d(tag, "Found M3U8 from packed: $m3u8Url")
                    
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        baseUrl,
                        headers = mapOf("Referer" to "$baseUrl/")
                    ).forEach(callback)
                    return
                }
            }

            // Direct m3u8/mp4 search
            val videoPatterns = listOf(
                Regex("""file\s*:\s*["']([^"']*\.(m3u8|mp4)[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']*\.(m3u8|mp4)[^"']*)["']"""),
                Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""")
            )

            for (pattern in videoPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = match.groupValues.getOrNull(1) ?: match.value
                    Log.d(tag, "Found video: $videoUrl")
                    
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, videoUrl, baseUrl).forEach(callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(name, name, videoUrl) { 
                                this.quality = Qualities.Unknown.value 
                            }
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HubCloud Extractor - MAIN EXTRACTOR
 * Handles: hubcloud.*, gamerxyt.com/hubcloud.php
 * This is the main extractor that provides direct download links
 */
class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        Log.d(tag, "Processing HubCloud URL: $url")

        try {
            val realUrl = url.takeIf {
                try { URI(it).toURL(); true } catch (e: Exception) { false }
            } ?: return

            val baseUrl = getBaseUrl(realUrl)

            // Step 1: Get the hubcloud.php download page URL
            val href = if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                // For hubcloud.foo/drive/xxx pages, find the "Generate Direct Download Link" button
                val doc = app.get(realUrl).document
                val rawHref = doc.selectFirst("a[href*=gamerxyt]")?.attr("href")
                    ?: doc.selectFirst("a[href*=hubcloud.php]")?.attr("href")
                    ?: doc.selectFirst("#download")?.attr("href")
                    ?: doc.selectFirst("a.btn")?.attr("href")
                    ?: ""
                
                if (rawHref.startsWith("http")) rawHref
                else if (rawHref.isNotBlank()) baseUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
                else ""
            }

            if (href.isBlank()) {
                Log.w(tag, "No valid download page found")
                return
            }

            Log.d(tag, "Download page: $href")

            // Step 2: Get the download buttons page
            val document = app.get(href).document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()

            val headerDetails = cleanTitle(header)
            val labelExtras = buildString {
                if (headerDetails.isNotEmpty()) append("[$headerDetails]")
                if (size.isNotEmpty()) append("[$size]")
            }
            
            val quality = getIndexQuality(header)
            Log.d(tag, "Quality: $quality, Size: $size")

            // Step 3: Process each download button
            document.select("div.card-body h2 a.btn, div.card-body a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()

                Log.d(tag, "Button: $text -> $link")

                when {
                    text.contains("FSL Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [FSL Server]",
                                "$referer [FSL Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("FSLv2", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [FSLv2]",
                                "$referer [FSLv2] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer",
                                "$referer $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                            val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$referer [BuzzServer]",
                                        "$referer [BuzzServer] $labelExtras",
                                        dlink
                                    ) { this.quality = quality }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "BuzzServer error: ${e.message}")
                        }
                    }

                    text.contains("pixeldra", ignoreCase = true) || 
                    text.contains("pixel", ignoreCase = true) -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                        callback(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $labelExtras",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("S3 Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [S3 Server]",
                                "$referer [S3 Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("Mega Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [Mega Server]",
                                "$referer [Mega Server] $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("10Gbps", ignoreCase = true) -> {
                        try {
                            var currentLink = link
                            var redirectUrl: String?
                            var redirectCount = 0
                            val maxRedirects = 3

                            while (redirectCount < maxRedirects) {
                                val response = app.get(currentLink, allowRedirects = false)
                                redirectUrl = response.headers["location"]

                                if (redirectUrl == null) {
                                    Log.e(tag, "10Gbps: No redirect")
                                    return@amap
                                }

                                if ("link=" in redirectUrl) {
                                    val finalLink = redirectUrl.substringAfter("link=")
                                    callback.invoke(
                                        newExtractorLink(
                                            "10Gbps [Download]",
                                            "10Gbps [Download] $labelExtras",
                                            finalLink
                                        ) { this.quality = quality }
                                    )
                                    return@amap
                                }

                                currentLink = redirectUrl
                                redirectCount++
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "10Gbps error: ${e.message}")
                        }
                    }

                    link.isNotBlank() && !text.contains("Watch", ignoreCase = true) -> {
                        // Generic download link
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("""(\d{3,4})[pP]""").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")

        val qualityTags = listOf(
            "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
            "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV", "HD"
        )

        val audioTags = listOf(
            "AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos"
        )

        val subTags = listOf(
            "ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub"
        )

        val codecTags = listOf(
            "x264", "x265", "H264", "HEVC", "AVC"
        )

        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        val endIndex = parts.indexOfLast { part ->
            subTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    audioTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    codecTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        return if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
            parts.subList(startIndex, endIndex + 1).joinToString(".")
        } else if (startIndex != -1) {
            parts.subList(startIndex, parts.size).joinToString(".")
        } else {
            parts.takeLast(3).joinToString(".")
        }
    }
}
