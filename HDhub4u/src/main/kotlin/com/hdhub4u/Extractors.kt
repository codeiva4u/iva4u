package com.hdhub4u

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
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
 * Uses Regex to extract download links
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
            val response = app.get(url, referer = referer)
            val html = response.text

            // Regex pattern to find download button/link
            val downloadLinkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*id="download"[^>]*>""", RegexOption.IGNORE_CASE)
            val downloadMatch = downloadLinkRegex.find(html)

            if (downloadMatch != null) {
                val downloadPage = downloadMatch.groupValues[1]
                val fullUrl = if (downloadPage.startsWith("http")) downloadPage else "$mainUrl$downloadPage"
                
                Log.d(tag, "Found download page: $fullUrl")
                
                // Follow to get actual download link
                val downloadResponse = app.get(fullUrl, referer = url)
                val downloadHtml = downloadResponse.text
                
                // Extract direct download link using Regex
                val directLinkRegex = Regex("""https?://[^\s"'<>]+\.(mp4|mkv|m3u8)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                val directMatch = directLinkRegex.find(downloadHtml)
                
                if (directMatch != null) {
                    val directUrl = directMatch.value
                    val quality = extractQualityFromUrl(url, html)
                    
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [${quality}p]",
                            directUrl
                        ) { this.quality = quality }
                    )
                    return
                }
                
                // Try HubCloud extractor
                HubCloud().getUrl(fullUrl, referer, subtitleCallback, callback)
            } else {
                // Try alternate patterns
                val altLinkRegex = Regex("""<a[^>]+href="([^"]+hubcloud[^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
                val altMatch = altLinkRegex.find(html)
                
                if (altMatch != null) {
                    HubCloud().getUrl(altMatch.groupValues[1], referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }

    private fun extractQualityFromUrl(url: String, html: String): Int {
        val qualityRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        return qualityRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: qualityRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

/**
 * GadgetsWeb Extractor
 * Handles: gadgetsweb.xyz/?id={base64_encoded_id}
 * Decodes Base64 ID and follows redirects
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
            // Extract Base64 ID using Regex
            val idRegex = Regex("""\?id=([A-Za-z0-9+/=]+)""")
            val idMatch = idRegex.find(url)
            
            if (idMatch != null) {
                val encodedId = idMatch.groupValues[1]
                Log.d(tag, "Encoded ID: $encodedId")
                
                // Decode Base64
                try {
                    val decodedBytes = Base64.decode(encodedId, Base64.DEFAULT)
                    val decodedUrl = String(decodedBytes)
                    Log.d(tag, "Decoded URL: $decodedUrl")
                    
                    if (decodedUrl.startsWith("http")) {
                        // Follow the decoded URL
                        when {
                            decodedUrl.contains("hubcloud", ignoreCase = true) ||
                            decodedUrl.contains("hubdrive", ignoreCase = true) -> {
                                HubCloud().getUrl(decodedUrl, referer, subtitleCallback, callback)
                            }
                            else -> {
                                loadExtractor(decodedUrl, referer ?: mainUrl, subtitleCallback, callback)
                            }
                        }
                        return
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Base64 decode error: ${e.message}")
                }
            }
            
            // Fallback: Fetch page and find redirect
            val response = app.get(url, referer = referer, allowRedirects = false)
            val locationHeader = response.headers["location"]
            
            if (!locationHeader.isNullOrBlank()) {
                Log.d(tag, "Redirect to: $locationHeader")
                when {
                    locationHeader.contains("hubcloud", ignoreCase = true) -> {
                        HubCloud().getUrl(locationHeader, referer, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(locationHeader, referer ?: mainUrl, subtitleCallback, callback)
                    }
                }
            } else {
                // Parse HTML for links
                val html = response.text
                val linkRegex = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
                
                linkRegex.findAll(html).forEach { match ->
                    val link = match.groupValues[1]
                    if (link.contains("hubcloud", ignoreCase = true) || 
                        link.contains("hubdrive", ignoreCase = true)) {
                        HubCloud().getUrl(link, referer, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HDStream4u Extractor
 * Handles: hdstream4u.com/file/{id}
 * Extracts streaming sources
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
            val patterns = listOf(
                Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""source\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""src\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
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

            // Try to find iframe and extract
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            iframeRegex.findAll(html).forEach { match ->
                val iframeSrc = match.groupValues[1]
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
 * Extracts video stream sources
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
                
                // Regex to find m3u8 in unpacked JS
                val m3u8Regex = Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""")
                val match = m3u8Regex.find(unpacked)
                
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
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

            // Direct m3u8 search
            val m3u8Patterns = listOf(
                Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']*\.m3u8[^"']*)["']"""),
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
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

            // Try mp4 direct links
            val mp4Regex = Regex("""https?://[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?""")
            val mp4Match = mp4Regex.find(html)
            if (mp4Match != null) {
                Log.d(tag, "Found MP4: ${mp4Match.value}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        mp4Match.value
                    ) { this.quality = Qualities.Unknown.value }
                )
            }

        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

/**
 * HubCloud Extractor
 * Handles: hubcloud.*, gamerxyt.com/hubcloud.php
 * Main extractor for download links
 */
class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
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

            // Get download page URL
            val href = if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val doc = app.get(realUrl).document
                val rawHref = doc.select("#download").attr("href")
                if (rawHref.startsWith("http")) rawHref
                else baseUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
            }

            if (href.isBlank()) {
                Log.w(tag, "No valid href found")
                return
            }

            val document = app.get(href).document
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val header = document.selectFirst("div.card-header")?.text().orEmpty()

            val headerDetails = cleanTitle(header)
            val labelExtras = buildString {
                if (headerDetails.isNotEmpty()) append("[$headerDetails]")
                if (size.isNotEmpty()) append("[$size]")
            }
            
            // Extract quality using Regex
            val quality = Regex("""(\d{3,4})p""").find(header)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.P2160.value

            document.select("div.card-body h2 a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()

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
                    }

                    text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrlLink/api/file/${link.substringAfterLast("/")}" + "?download"

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
                                "$referer S3 Server",
                                "$referer S3 Server $labelExtras",
                                link
                            ) { this.quality = quality }
                        )
                    }

                    text.contains("FSLv2", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$referer FSLv2",
                                "$referer FSLv2 $labelExtras",
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
                    }

                    else -> {
                        loadExtractor(link, "", subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            e.printStackTrace()
        }
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
