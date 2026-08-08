package com.movies4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "*/*"
)

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
            // embed.php?download= URL → redirect follow करो
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url

            // अगर redirect हुआ और CDN पर पहुंचे
            if (finalUrl != url && finalUrl.startsWith("http") && !shouldBlockUrl(finalUrl)) {
                callback(newExtractorLink(name, name, finalUrl) {
                    this.quality = Qualities.Unknown.value
                    this.headers = VIDEO_HEADERS
                })
                return
            }

            // No redirect — fallback: location header या link= / download= param
            val loc = response.headers["location"].orEmpty()
            val targetUrl = if (loc.isNotBlank()) loc else finalUrl
            val videoUrl = when {
                targetUrl.contains("link=") ->
                    java.net.URLDecoder.decode(targetUrl.substringAfter("link="), "UTF-8")
                targetUrl.contains("download=") && !targetUrl.contains("embed.php") ->
                    java.net.URLDecoder.decode(targetUrl.substringAfter("download="), "UTF-8")
                else -> {
                    // Page parse करो
                    val doc = response.document
                    val directLink = doc.selectFirst(
                        "a[href*='r2.dev'], a[href*='cloudflarestorage.com'], a[href*='busycdn'], " +
                        "a[href*='link='], a[href*='download='], a[href$='.mkv'], a[href$='.mp4']"
                    )?.attr("href") ?: ""
                    when {
                        directLink.contains("link=") -> java.net.URLDecoder.decode(directLink.substringAfter("link="), "UTF-8")
                        directLink.contains("download=") -> java.net.URLDecoder.decode(directLink.substringAfter("download="), "UTF-8")
                        directLink.startsWith("http") -> directLink
                        else -> ""
                    }
                }
            }
            if (videoUrl.isNotBlank() && videoUrl.startsWith("http") && !shouldBlockUrl(videoUrl)) {
                callback(newExtractorLink(name, name, videoUrl) {
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
            val innerLink = doc.selectFirst("a[href*='hubcloud'], a[href*='fastdl'], a[href*='filebee'], a[href*='gdflix'], a[href*='m4ulinks'], a[href*='mdrive']")?.attr("href") ?: ""
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

            link.contains("m4ulinks", true) || link.contains("mdrive", true) ->
                M4uLinks().getUrl(link, referer, subtitleCallback, callback)

            link.contains("linksmod", true) ->
                LinksMod().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hubcloud", true) || link.contains("gamerxyt", true) ->
                HubCloud().getUrl(link, referer, subtitleCallback, callback)

            link.contains("gdflix", true) || link.contains("gdlink", true) ->
                GDFlix().getUrl(link, referer, subtitleCallback, callback)

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

            // Direct download CDN links — instantly usable
            link.contains("r2.dev", true) ||
            link.contains("r2.cloudflarestorage.com", true) ||
            link.contains("fsl-buckets", true) ||
            link.contains("busycdn", true) ||
            link.contains("indexserver", true) ||
            link.contains("video-downloads.googleusercontent.com", true) ||
            link.endsWith(".mkv", true) ||
            link.endsWith(".mp4", true) -> {
                callback(newExtractorLink(
                    "Direct", "Direct Download", link
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

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

// Follow multi-hop redirect chains (gpdl.hubcloud.cx -> workers.dev -> gamerxyt.com/dl.php?link=FINAL)
// and extract the final real download URL, decoding the link= parameter.
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
        ".m3u8", "/hls/", ".mpd",
        "hubstream", "hdstream", "hdstream4u",
        "t.me/", "tinyurl.com",
        "google.com/search", "one.one.one.one",
        "/tg/go", "voe.sx", "streamtape", "streamsb", "mixdrop",
        "doodstream", "vidhide", "streamhub", "uqload", "dood.", "doodrive",
        "m4uplay", "morencius", "earnvids",
        "telegram.org", "telegram.me"
    )
    if (blockList.any { url.contains(it, ignoreCase = true) }) return true
    if (url.endsWith(".zip", ignoreCase = true)) return true
    return false
}

fun isInvalidLink(url: String, text: String): Boolean {
    if (shouldBlockUrl(url)) return true
    val lowerText = text.lowercase()
    if (lowerText.contains("zip") && !lowerText.contains("fastdl.zip") && !lowerText.contains("vcloud.zip")) return true
    if (lowerText.contains("telegram") || lowerText.contains("login")) return true
    return false
}

open class M4uLinks : ExtractorApi() {
    override val name = "M4uLinks"
    override val mainUrl = "https://(?:m4ulinks|mdrive|linksmod)\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "M4uLinks"
        Log.d(tag, "Processing: $url")

        try {
            val doc = app.get(url).document
            // mdrive.buzz/mdisk/ पर: r2.dev, fastdl.zip, filebee.xyz, hubcloud.cx, gdflix.dev
            doc.select("a[href]").amap { element ->
                val abs = element.absUrl("href")
                val href = if (abs.isNotBlank()) abs else element.attr("href")
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                if (shouldBlockUrl(href)) return@amap

                val isDownloadLink = href.contains("hubcloud", true) ||
                    href.contains("hubcdn", true) ||
                    href.contains("hubdrive", true) ||
                    href.contains("gdflix", true) ||
                    href.contains("gdlink", true) ||
                    href.contains("pixeldrain", true) ||
                    href.contains("gofile.io", true) ||
                    href.contains("filebee", true) ||
                    href.contains("filepress", true) ||
                    href.contains("fastdl", true) ||
                    href.contains("vcloud", true) ||
                    href.contains("megaup", true) ||
                    href.contains("vikingfile", true) ||
                    href.contains("1fichier", true) ||
                    href.contains("multiup", true) ||
                    href.contains("linksmod", true) ||
                    href.contains("r2.dev", true) ||
                    href.contains("r2.cloudflarestorage.com", true) ||
                    href.contains("busycdn", true) ||
                    href.contains("indexserver", true) ||
                    href.endsWith(".mkv", true) ||
                    href.endsWith(".mp4", true)

                if (!isDownloadLink) return@amap

                try {
                    processPluginExtractor(href, name, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(tag, "Failed inner link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing m4ulinks: ${e.message}")
        }
    }
}

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.*"
}

open class LinksMod : ExtractorApi() {
    override val name = "LinksMod"
    override val mainUrl = "https://linksmod\\.top"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "LinksMod"
        Log.d(tag, "Processing: $url")
        try {
            val doc = app.get(url).document
            doc.select(
                "a[href*='hubcloud'], a[href*='gdflix'], a[href*='fastdl'], a[href*='pixeldrain']," +
                "a[href*='filebee'], a[href*='filepress'], a[href*='gofile'], a[href*='megaup']," +
                "a[href*='vikingfile'], a[href*='1fichier'], a[href*='multiup'], a[href*='r2.dev']," +
                "a[href*='busycdn'], a[href*='indexserver']"
            ).forEach { elem ->
                val abs = elem.absUrl("href")
                val href = if (abs.isNotBlank()) abs else elem.attr("href")
                if (href.isBlank() || shouldBlockUrl(href)) return@forEach
                try {
                    processPluginExtractor(href, referer, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(tag, "Failed inner link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
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
            val driveHtml = driveDoc.html()

            val header = driveDoc.selectFirst("div.card-header")?.text() ?: ""
            val size = driveDoc.selectFirst("i#size")?.text() ?: ""

            val qualityMatch = Regex("""(\d{3,4})p""").find(header)
            val quality = qualityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1080
            val codec = when {
                header.contains("x264", true) || header.contains("h264", true) -> "x264"
                header.contains("hevc", true) || header.contains("x265", true) -> "hevc"
                else -> ""
            }

            val labelExtras = buildString {
                if (header.isNotEmpty()) append("[$header]")
                if (size.isNotEmpty()) append("[$size]")
            }

            var tokenUrl = ""
            if (newUrl.contains("?token=")) {
                tokenUrl = newUrl
            }

            if (tokenUrl.isBlank()) {
                val downloadHref = driveDoc.selectFirst("a#download")?.attr("href") ?: ""
                if (downloadHref.isNotBlank() && downloadHref.contains("token=")) {
                    tokenUrl = if (downloadHref.startsWith("http")) downloadHref
                    else latestUrl.trimEnd('/') + "/" + downloadHref.trimStart('/')
                }
            }

            if (tokenUrl.isBlank()) {
                val jsPattern = Regex("""var\s+url\s*=\s*['"]([^'"]*\?token=[^'"]+)['"]""")
                val jsMatch = jsPattern.find(driveHtml)
                if (jsMatch != null) {
                    val jsUrl = jsMatch.groupValues[1]
                    tokenUrl = if (jsUrl.startsWith("http")) jsUrl
                    else latestUrl.trimEnd('/') + "/" + jsUrl.trimStart('/')
                }
            }

            // Jul 2026: New structure - drive page has a "Generate Direct Download Link" button
            // pointing to gamerxyt.com/hubcloud.php?host=hubcloud&id=...&token=...
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
                    Log.d(tag, "Found Generate Direct Download Link: $generateHref")
                }
            }

            if (tokenUrl.isBlank()) {
                Log.w(tag, "No token URL found for: $newUrl")
                return
            }

            val document = app.get(tokenUrl).document
            val finalHeader = header.ifEmpty { document.selectFirst("div.card-header")?.text() ?: "" }
            val finalSize = size.ifEmpty { document.selectFirst("i#size")?.text() ?: "" }
            val finalLabel = labelExtras.ifEmpty {
                buildString {
                    if (finalHeader.isNotEmpty()) append("[$finalHeader]")
                    if (finalSize.isNotEmpty()) append("[$finalSize]")
                }
            }

            // Jul 2026: Download buttons are now OUTSIDE div.card-body (inside <h2> or ul), so scan for cloudflarestorage / pixel.hubcloud / pixeldrain / gpdl
            val downloadButtons = document.select("a[href*='cloudflarestorage.com'], a[href*='fsl-buckets'], a[href*='pixel.hubcloud'], a[href*='pixeldrain'], a[href*='gpdl'], div.card-body a, a.btn, a.btn-lg, a[href*='http']")
            downloadButtons.amap { element ->
                val link = element.attr("href")
                val text = element.text()

                if (link.isBlank() || !link.startsWith("http")) return@amap
                if (shouldBlockUrl(link)) return@amap

                val skipTexts = listOf("Telegram", "IDM", "IDA", "VPN", "Tutorial", "Copy", "Login", "Create", "How", "Report")
                if (skipTexts.any { text.contains(it, true) }) return@amap

                if (text.contains("ZipDisk", true) || link.contains("zipdisk", true) ||
                    link.endsWith(".zip", true) || link.contains("cloudserver", true)) {
                    return@amap
                }

                val score = getAdjustedQuality(quality, finalSize, text, finalHeader)

                try {
                    when {
                        text.contains("FSLv2", true) || link.contains("fsl-buckets", true) || link.contains("fsl.gigabytes", true) -> {
                            callback(newExtractorLink(
                                "$name [FSLv2]", "$name [FSLv2] $finalLabel", link
                            ) {
                                this.quality = score + 20
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        link.contains("r2.cloudflarestorage.com", true) || link.contains("fsl-buckets", true) || link.contains("diskcdn.buzz", true) -> {
                            callback(newExtractorLink(
                                "$name [FSL]", "$name [FSL] $finalLabel", link
                            ) {
                                this.quality = score + 15
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        text.contains("10Gbps", true) || (link.contains("pixel.hubcloud", true) && link.contains("?id=")) -> {
                            try {
                                // Jul 2026: 10Gbps is gpdl.hubcloud.cx with multi-hop redirect chain
                                val downloadUrl = resolveHubCloudDirect(link)
                                if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http") &&
                                    !downloadUrl.contains("hubcloud.cx", true) &&
                                    !downloadUrl.contains("gamerxyt.com", true) &&
                                    !downloadUrl.contains("/bgmi/", true)) {
                                    callback(newExtractorLink(
                                        "10Gbps", "10Gbps $finalLabel", downloadUrl
                                    ) {
                                        this.quality = score + 10
                                        this.headers = VIDEO_HEADERS
                                    })
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "10Gbps redirect error: ${e.message}")
                            }
                        }
                        (link.contains("video-downloads.googleusercontent.com", true) || link.endsWith(".mkv", true) || link.endsWith(".mp4", true)) -> {
                            callback(newExtractorLink(
                                "$name [Direct]", "$name [Direct] $finalLabel", link
                            ) {
                                this.quality = score + 25
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        link.contains("pixeldrain", true) -> {
                            val finalURL = if (link.contains("/u/")) {
                                "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                            } else link
                            callback(newExtractorLink(
                                "Pixeldrain", "Pixeldrain $finalLabel", finalURL
                            ) {
                                this.quality = score
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        text.contains("Download", true) && !link.contains("google.com", true) -> {
                            callback(newExtractorLink(
                                name, "$name $finalLabel", link
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
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink\\..*"
}

class GDFlixNet : GDFlix() {
    override var mainUrl = "https://(.*gdflix|gdlink)\\..*"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://(.*gdflix|gdlink)\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (shouldBlockUrl(url)) return

        var latestUrl = getLatestUrl(url, "gdflix")
        val gdflix2 = getLatestUrl(url, "gdflix2")
        if (gdflix2.isNotEmpty() && !gdflix2.contains("gdflix2")) {
            latestUrl = gdflix2
        }

        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val baseQuality = getIndexQuality(fileName)

        val fastServers = mutableListOf<org.jsoup.nodes.Element>()
        val slowServers = mutableListOf<org.jsoup.nodes.Element>()

        document.select("div.text-center a").forEach { anchor ->
            // BUG FIX: anchor खुद <a> है, anchor.select("a") always empty → anchor.text() use करो
            val text = anchor.text().trim()
            val href = anchor.attr("href")

            if (href.isBlank() || shouldBlockUrl(href)) return@forEach
            if (text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) ||
                text.contains("ZIP", true) || href.contains(".zip", true) ||
                text.contains("Telegram", true) || text.contains("Login", true)) return@forEach

            when {
                text.contains("Instant DL", true) || text.contains("DIRECT DL", true) ||
                text.contains("DIRECT SERVER", true) || text.contains("CLOUD DOWNLOAD", true) ||
                href.contains("pixeldra", true) -> fastServers.add(anchor)
                else -> slowServers.add(anchor)
            }
        }

        for (anchor in fastServers) {
            val text = anchor.text().trim()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)

            try {
                when {
                    text.contains("Instant DL", true) -> {
                        val instantLink = try {
                            val location = app.get(link, allowRedirects = false).headers["location"].orEmpty()
                            if (location.isNotEmpty()) location.substringAfter("url=").ifEmpty { location } else link
                        } catch (_: Exception) { link }
                        if (instantLink.isNotBlank() &&
                            !instantLink.contains("gamerxyt.com", true) &&
                            !instantLink.contains("/bgmi/", true)) {
                            callback.invoke(newExtractorLink(
                                "GDFlix[Instant]", "GDFlix[Instant] $fileName [$fileSize]", instantLink
                            ) {
                                this.quality = serverQuality + 800
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                    text.contains("CLOUD DOWNLOAD", true) -> {
                        val r2Link = if (link.contains("url="))
                            URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        else link
                        if (r2Link.isNotBlank() && (r2Link.contains("r2.dev", true) ||
                            r2Link.contains("cloudflarestorage", true))) {
                            callback.invoke(newExtractorLink(
                                "GDFlix[R2-Cloud]", "GDFlix[R2-Cloud] $fileName [$fileSize]", r2Link
                            ) {
                                this.quality = serverQuality + 700
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                    text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) -> {
                        callback.invoke(newExtractorLink(
                            "GDFlix[Direct]", "GDFlix[Direct] $fileName [$fileSize]", link
                        ) {
                            this.quality = serverQuality + 600
                            this.headers = VIDEO_HEADERS
                        })
                    }
                    link.contains("pixeldra", true) -> {
                        val pdUrl = if (link.contains("download", true)) link
                        else "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                        callback.invoke(newExtractorLink(
                            "GDFlix[Pixeldrain]", "GDFlix[Pixeldrain] $fileName [$fileSize]", pdUrl
                        ) {
                            this.quality = serverQuality + 400
                            this.headers = VIDEO_HEADERS
                        })
                    }
                }
            } catch (e: Exception) {
                Log.d("GDFlix Fast", e.toString())
            }
        }

        for (anchor in slowServers) {
            val text = anchor.text().trim()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)

            try {
                when {
                    text.contains("Index Links", true) -> {
                        val indexDoc = app.get("$latestUrl$link").document
                        val firstServer = indexDoc.selectFirst("a.btn.btn-outline-info")
                        if (firstServer != null) {
                            val serverUrl = latestUrl + firstServer.attr("href")
                            val sourceAnchor = app.get(serverUrl).document.selectFirst("div.mb-4 > a")
                            if (sourceAnchor != null) {
                                val source = sourceAnchor.attr("href")
                                callback.invoke(newExtractorLink(
                                    "GDFlix[Index]", "GDFlix[Index] $fileName [$fileSize]", source
                                ) {
                                    this.quality = serverQuality + 300
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                    }
                    text.contains("GoFile", true) || link.contains("gofile", true) ||
                    link.contains("multiup", true) -> {
                        callback.invoke(newExtractorLink(
                            "GDFlix[Mirror]", "GDFlix[Mirror] $fileName [$fileSize]", link
                        ) {
                            this.quality = serverQuality + 200
                            this.headers = VIDEO_HEADERS
                        })
                    }
                    link.contains("indexserver", true) -> {
                        callback.invoke(newExtractorLink(
                            "GDFlix[IndexSrv]", "GDFlix[IndexSrv] $fileName [$fileSize]", link
                        ) {
                            this.quality = serverQuality + 350
                            this.headers = VIDEO_HEADERS
                        })
                    }
                    text.contains("DRIVEBOT", true) -> {
                        val id = link.substringAfter("id=").substringBefore("&")
                        val doId = link.substringAfter("do=").substringBefore("==")
                        val driveBotBaseUrl = "https://drivebot.sbs"
                        val indexbotLink = "$driveBotBaseUrl/download?id=$id&do=$doId"
                        val indexbotResponse = app.get(indexbotLink, timeout = 30000L)

                        if (indexbotResponse.isSuccessful) {
                            val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                            val indexbotDoc = indexbotResponse.document

                            val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""").find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()
                            val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""").find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                            val requestBody = FormBody.Builder().add("token", token).build()
                            val postHeaders = mapOf("Referer" to indexbotLink)
                            val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                            val downloadLink = app.post(
                                "$driveBotBaseUrl/download?id=$postId",
                                requestBody = requestBody,
                                headers = postHeaders,
                                cookies = cookies,
                                timeout = 30000L
                            ).text.let {
                                Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                            }

                            if (downloadLink.isNotEmpty()) {
                                callback.invoke(newExtractorLink(
                                    "GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName [$fileSize]", downloadLink
                                ) {
                                    this.referer = driveBotBaseUrl
                                    this.quality = serverQuality + 100
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("GDFlix Slow", e.toString())
            }
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
            when {
                newUrl.contains("/file/") -> {
                    val response = app.get(newUrl, allowRedirects = true)
                    val finalPageUrl = response.url
                    val doc = response.document
                    var downloadUrl: String? = null

                    if (finalPageUrl.contains("link=")) {
                        downloadUrl = URLDecoder.decode(finalPageUrl.substringAfter("link="), "UTF-8")
                    }

                    if (downloadUrl.isNullOrBlank() && finalPageUrl.contains("inventoryidea.com")) {
                        try {
                            val rParam = Regex("""[?&]r=([A-Za-z0-9+/=]+)""").find(finalPageUrl)?.groupValues?.get(1) ?: ""
                            if (rParam.isNotEmpty()) {
                                val decoded = String(android.util.Base64.decode(rParam, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                if (decoded.contains("link=")) {
                                    downloadUrl = URLDecoder.decode(decoded.substringAfter("link="), "UTF-8")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to decode inventoryidea r param: ${e.message}")
                        }
                    }

                    if (downloadUrl.isNullOrBlank()) {
                        val dlLink = doc.selectFirst("a[href]:contains(Download Here)")?.attr("href")
                            ?: doc.selectFirst("a.btn[href^=http]")?.attr("href")
                            ?: doc.selectFirst("a.get-link[href^=http]")?.attr("href")
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
                }
                newUrl.contains("hubcdn") -> {
                    val doc = app.get(newUrl).document
                    val hubcloudLink = doc.select("a[href*=hubcloud]").attr("href")
                    if (hubcloudLink.isNotBlank()) {
                        HubCloud().getUrl(hubcloudLink, referer, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.*"
    override val requiresReferer = false

    @Suppress("UNUSED_PARAMETER")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestMainUrl = getLatestUrl(url, "gofile")
        val latestApiUrl = latestMainUrl.replace("://", "://api.")

        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to latestMainUrl,
            "Referer" to latestMainUrl,
        )
        val id = url.substringAfter("d/").substringBefore("/")
        val genAccountRes = app.post("$latestApiUrl/accounts", headers = requestHeaders).text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token")
        val globalRes = app.get("$latestMainUrl/dist/js/config.js", headers = requestHeaders).text
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get(
            "$latestApiUrl/contents/$id?cache=true&sortField=createTime&sortDirection=1",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Origin" to latestMainUrl,
                "Referer" to latestMainUrl,
                "Authorization" to "Bearer $token",
                "X-Website-Token" to wt
            )
        ).text

        val jsonResponse = JSONObject(response)
        val data = jsonResponse.getJSONObject("data")
        val children = data.getJSONObject("children")
        val oId = children.keys().next()
        val link = children.getJSONObject(oId).getString("link")
        val fileName = children.getJSONObject(oId).getString("name")
        val size = children.getJSONObject(oId).getLong("size")
        val formattedSize = if (size < 1024L * 1024 * 1024) {
            "%.2f MB".format(size.toDouble() / (1024 * 1024))
        } else {
            "%.2f GB".format(size.toDouble() / (1024 * 1024 * 1024))
        }

        callback.invoke(
            newExtractorLink("Gofile", "Gofile $fileName[$formattedSize]", link) {
                this.quality = getIndexQuality(fileName)
                this.headers = VIDEO_HEADERS + mapOf("Cookie" to "accountToken=$token")
            }
        )
    }
}
