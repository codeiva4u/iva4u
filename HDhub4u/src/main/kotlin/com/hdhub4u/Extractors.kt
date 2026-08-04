package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import org.json.JSONObject
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

suspend fun resolveHubCloudDirect(startUrl: String): String {
    var current = startUrl
    try {
        repeat(6) {
            val response = app.get(current, allowRedirects = false)
            val location = response.headers["location"].orEmpty()
            if (location.isBlank()) {
                current = if (current.contains("link=")) {
                    URLDecoder.decode(current.substringAfter("link="), StandardCharsets.UTF_8.toString())
                } else {
                    current
                }
                return@repeat
            }
            current = if (location.startsWith("http")) location else getBaseUrl(current) + location
            if (current.contains("link=")) {
                val decoded = URLDecoder.decode(current.substringAfter("link="), StandardCharsets.UTF_8.toString())
                if (decoded.isNotEmpty() && decoded.startsWith("http")) {
                    current = decoded
                }
            }
        }
    } catch (e: Exception) {
        Log.e("HubCloud", "Redirect resolve failed: ${e.message}")
    }
    return current.ifEmpty { startUrl }
}

private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
        } catch (e: Exception) {
            return getBaseUrl(url)
        }
    }
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").replace("⚡", "").trim()
    val regex = Regex("""([\d.]+)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
    val match = regex.find(cleanSize) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    val unit = match.groupValues[2].uppercase()
    return when (unit) {
        "GB" -> value * 1024
        "MB" -> value
        else -> Double.MAX_VALUE
    }
}

fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 800
        serverName.contains("10Gbps", true) -> 750
        serverName.contains("FSLv2", true) -> 700
        serverName.contains("FSL", true) -> 600
        serverName.contains("Direct", true) -> 500
        serverName.contains("Pixeldrain", true) -> 400
        serverName.contains("Download File", true) -> 300
        else -> 100
    }
}

fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()

    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")

    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000
        isX264 && quality >= 720  -> 20000
        isHEVC && quality >= 1080 -> 10000
        isHEVC && quality >= 720  -> 9000
        quality >= 1080 -> 8000
        quality >= 720  -> 7000
        quality >= 480  -> 6000
        else -> 5000
    }

    val sizeMB = parseSizeToMB(sizeStr)
    val sizeScore = when {
        sizeMB <= 300  -> 260
        sizeMB <= 400  -> 250
        sizeMB <= 500  -> 240
        sizeMB <= 600  -> 230
        sizeMB <= 700  -> 220
        sizeMB <= 800  -> 210
        sizeMB <= 900  -> 200
        sizeMB <= 1000 -> 190
        sizeMB <= 1200 -> 170
        sizeMB <= 1500 -> 140
        sizeMB <= 2000 -> 100
        sizeMB <= 2500 -> 60
        sizeMB <= 3000 -> 20
        else -> 0
    }

    val serverScore = getServerPriority(serverName)
    return codecQualityScore + sizeScore + serverScore
}

fun shouldBlockUrl(url: String): Boolean {
    val blockList = listOf(
        ".m3u8", "/hls/", "hubstream", "hdstream",
        "hdstream4u", "t.me/", "tinyurl.com",
        "google.com/search", "one.one.one.one",
        "/tg/go", "voe.sx", "streamtape", "streamsb", "mixdrop",
        "doodstream", "vidhide", "streamhub", "uqload", "dood.", "doodrive",
        "m4uplay", "morencius", "earnvids"
    )
    return blockList.any { url.contains(it, ignoreCase = true) }
}

open class FastDLExtractor : ExtractorApi() {
    override val name = "FastDL"
    override val mainUrl = "https://fastdl\\.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, allowRedirects = false)
            val loc = response.headers["location"].orEmpty()
            val targetUrl = if (loc.isNotBlank()) loc else url
            val videoUrl = if (targetUrl.contains("link=")) {
                URLDecoder.decode(targetUrl.substringAfter("link="), StandardCharsets.UTF_8.toString())
            } else {
                val doc = app.get(url).document
                val linkAttr = doc.selectFirst("a[href*='link=']")?.attr("href") ?: ""
                if (linkAttr.contains("link=")) {
                    URLDecoder.decode(linkAttr.substringAfter("link="), StandardCharsets.UTF_8.toString())
                } else ""
            }
            if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                callback(newExtractorLink(
                    name, name, videoUrl
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class VCloudExtractor : ExtractorApi() {
    override val name = "VCloud"
    override val mainUrl = "https://vcloud\\.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val innerLink = doc.selectFirst("a[href*='hubcloud'], a[href*='fastdl'], a[href*='filebee'], a[href*='gdflix'], a[href*='m4ulinks'], a[href*='mdrive'], a[href*='howblogs'], a[href*='linkstaker'], a[href*='hblinks']")?.attr("href") ?: ""
            if (innerLink.isNotBlank() && innerLink.startsWith("http")) {
                processPluginExtractor(innerLink, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class FilebeeExtractor : ExtractorApi() {
    override val name = "Filebee"
    override val mainUrl = "https://filebee\\.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val directLink = doc.selectFirst("a[href*='googleusercontent.com'], a[href*='r2.cloudflarestorage.com'], a[href*='pixeldrain.dev'], a[href*='gofile.io']")?.attr("href") ?: ""
            if (directLink.isNotBlank() && directLink.startsWith("http")) {
                callback(newExtractorLink(
                    name, name, directLink
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

suspend fun processPluginExtractor(
    link: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (shouldBlockUrl(link)) return

    try {
        when {
            link.contains("fastdl", true) ->
                FastDLExtractor().getUrl(link, referer, subtitleCallback, callback)

            link.contains("vcloud", true) ->
                VCloudExtractor().getUrl(link, referer, subtitleCallback, callback)

            link.contains("filebee", true) || link.contains("filepress", true) ->
                FilebeeExtractor().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hblinks", true) ->
                Hblinks().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hubdrive", true) ->
                Hubdrive().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hubcloud", true) || link.contains("gamerxyt", true) ->
                HubCloud().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hubcdn", true) ->
                HUBCDN().getUrl(link, referer, subtitleCallback, callback)

            link.contains("pixeldrain", true) -> {
                val finalURL = if (link.contains("/u/")) {
                    "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                } else link
                callback(newExtractorLink(
                    "Pixeldrain", "Pixeldrain", finalURL
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
            }

            link.contains("video-downloads.googleusercontent.com", true) ||
            link.contains("r2.cloudflarestorage.com", true) ||
            link.endsWith(".mkv", true) ||
            link.endsWith(".mp4", true) -> {
                callback(newExtractorLink(
                    "Direct", "Direct Stream", link
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
            }
        }
    } catch (e: Exception) {
        Log.e("PluginExtractor", "Error: ${e.message}")
    }
}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hblinks"
        val latestUrl = getLatestUrl(url, "4khdhub")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            val doc = app.get(newUrl).document
            val links = doc.select("a[href*='hubdrive'], a[href*='hubcloud'], a[href*='hubcdn'], a[href*='pixeldrain'], a[href*='gofile.io']")

            Log.d(tag, "Found ${links.size} links on hblinks page")

            links.amap { element ->
                val abs = element.absUrl("href")
                val href = if (abs.isNotBlank()) abs else element.attr("href")
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                if (shouldBlockUrl(href)) return@amap

                try {
                    processPluginExtractor(href, name, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(tag, "Failed inner link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing hblinks: ${e.message}")
        }
    }
}

open class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Hubdrive"
        val latestUrl = getLatestUrl(url, "hubdrive")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            val doc = app.get(newUrl).document
            val links = doc.select("a[href*='hubcloud'], a[href*='hubcdn'], a[href*='pixeldrain'], a[href*='gofile.io']")

            Log.d(tag, "Found ${links.size} links on hubdrive page")

            links.amap { element ->
                val abs = element.absUrl("href")
                val href = if (abs.isNotBlank()) abs else element.attr("href")
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                if (shouldBlockUrl(href)) return@amap

                try {
                    processPluginExtractor(href, name, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(tag, "Failed inner link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing hubdrive: ${e.message}")
        }
    }
}

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.*"
}

open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        if (shouldBlockUrl(url)) return

        val latestUrl = getLatestUrl(url, "hubcloud")
        val currentBaseUrl = getBaseUrl(url)
        val newUrl = url.replace(currentBaseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            val driveDoc = app.get(newUrl).document
            val header = driveDoc.selectFirst("div.card-header")?.text() ?: ""
            val size = driveDoc.selectFirst("i#size")?.text() ?: ""

            val qualityMatch = Regex("""(\d{3,4})p""").find(header)
            val quality = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1080

            var tokenUrl = ""
            if (newUrl.contains("?token=")) {
                tokenUrl = newUrl
            }

            if (tokenUrl.isBlank()) {
                val generateHref = driveDoc.selectFirst("a.btn.btn-primary.h6")?.attr("href")
                    ?: driveDoc.selectFirst("a.btn[href*=gamerxyt.com/hubcloud.php]")?.attr("href")
                    ?: driveDoc.selectFirst("a.btn[href*='?token=']")?.attr("href")
                    ?: driveDoc.selectFirst("a.btn[href*=hubcloud.php]")?.attr("href")
                    ?: driveDoc.select("a.btn").firstOrNull {
                        it.attr("href").contains("gamerxyt", true) || it.attr("href").contains("hubcloud.php", true)
                    }?.attr("href")
                    ?: ""
                if (generateHref.isNotBlank() && generateHref.startsWith("http")) {
                    tokenUrl = generateHref
                }
            }

            if (tokenUrl.isBlank()) return

            val document = app.get(tokenUrl).document
            val downloadButtons = document.select("a[href*='cloudflarestorage.com'], a[href*='fsl-buckets'], a[href*='pixel.hubcloud'], a[href*='pixeldrain'], a[href*='gpdl'], div.card-body a, a.btn, a.btn-lg, a[href*='http']")
            downloadButtons.amap { element ->
                val link = element.attr("href")
                val text = element.text()

                if (link.isBlank() || !link.startsWith("http")) return@amap
                if (shouldBlockUrl(link)) return@amap

                val skipTexts = listOf("Telegram", "IDM", "IDA", "VPN", "Tutorial", "Copy", "Login", "Create", "How", "Report")
                if (skipTexts.any { text.contains(it, true) }) return@amap

                val score = getAdjustedQuality(quality, size, text, header)

                try {
                    when {
                        text.contains("FSLv2", true) || link.contains("fsl-buckets", true) || link.contains("fsl.gigabytes", true) -> {
                            callback(newExtractorLink(
                                "$name [FSLv2]", "$name [FSLv2]", link
                            ) {
                                this.quality = score + 20
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        link.contains("r2.cloudflarestorage.com", true) || link.contains("fsl-buckets", true) || link.contains("diskcdn.buzz", true) -> {
                            callback(newExtractorLink(
                                "$name [FSL]", "$name [FSL]", link
                            ) {
                                this.quality = score + 15
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        text.contains("10Gbps", true) || (link.contains("pixel.hubcloud", true) && link.contains("?id=")) -> {
                            try {
                                val downloadUrl = resolveHubCloudDirect(link)
                                if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http") &&
                                    !downloadUrl.contains("hubcloud.cx", true) &&
                                    !downloadUrl.contains("gamerxyt.com", true)) {
                                    callback(newExtractorLink(
                                        "10Gbps", "10Gbps", downloadUrl
                                    ) {
                                        this.quality = score + 10
                                        this.headers = VIDEO_HEADERS
                                    })
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "10Gbps redirect error: ${e.message}")
                            }
                        }
                        link.contains("pixeldrain", true) -> {
                            val finalURL = if (link.contains("/u/")) {
                                "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                            } else link
                            callback(newExtractorLink(
                                "Pixeldrain", "Pixeldrain", finalURL
                            ) {
                                this.quality = score
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        text.contains("Download", true) && !link.contains("google.com", true) -> {
                            callback(newExtractorLink(
                                name, name, link
                            ) {
                                this.quality = score
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing button: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing HubCloud: ${e.message}")
        }
    }
}

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HUBCDN"
        val latestUrl = getLatestUrl(url, "hubcdn")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            val response = app.get(newUrl, allowRedirects = true)
            val finalPageUrl = response.url
            val doc = response.document
            var downloadUrl: String? = null

            if (finalPageUrl.contains("link=")) {
                downloadUrl = URLDecoder.decode(finalPageUrl.substringAfter("link="), "UTF-8")
            }

            if (downloadUrl.isNullOrBlank()) {
                val dlLink = doc.selectFirst("a[href]:contains(Download Here)")?.attr("href")
                    ?: doc.selectFirst("a.btn[href^=http]")?.attr("href")
                if (!dlLink.isNullOrBlank() && !dlLink.contains("hubcdn")) {
                    downloadUrl = dlLink
                }
            }

            if (!downloadUrl.isNullOrBlank() && downloadUrl.startsWith("http")) {
                callback(newExtractorLink("Instant DL", "Instant DL [HUBCDN]", downloadUrl, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}
