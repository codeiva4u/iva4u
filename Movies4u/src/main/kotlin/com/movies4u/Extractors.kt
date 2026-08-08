package com.movies4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "*/*"
)

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

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
        serverName.contains("Gofile", true) -> 550
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
        "telegram.org", "telegram.me",
        "linksmod"
    )
    if (blockList.any { url.contains(it, ignoreCase = true) }) return true
    if (url.endsWith(".zip", ignoreCase = true) && !url.contains("fastdl.zip", ignoreCase = true) && !url.contains("vcloud.zip", ignoreCase = true)) return true
    return false
}

fun isInvalidLink(url: String, text: String): Boolean {
    if (shouldBlockUrl(url)) return true
    val lowerText = text.lowercase()
    if (lowerText.contains("zip") && !lowerText.contains("fastdl.zip") && !lowerText.contains("vcloud.zip")) return true
    if (lowerText.contains("telegram") || lowerText.contains("login") || lowerText.contains("linksmod")) return true
    return false
}

open class FastDLServer : ExtractorApi() {
    override val name = "FastDLServer"
    override val mainUrl = "https://dl\\.fastdlserver\\.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            if (finalUrl.isNotBlank() && finalUrl != url) {
                processPluginExtractor(finalUrl, referer, subtitleCallback, callback)
            } else {
                val doc = response.document
                val innerLink = doc.selectFirst("a[href*='gdflix'], a[href*='hubcloud'], a[href*='fastdl'], a[href*='filebee']")?.attr("href") ?: ""
                if (innerLink.isNotBlank()) {
                    processPluginExtractor(innerLink, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class FxLinks : ExtractorApi() {
    override val name = "FxLinks"
    override val mainUrl = "https://fxlinks\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            doc.select("a[href*='fastdlserver'], a[href*='gdflix'], a[href*='hubcloud'], a[href*='fastdl'], a[href*='gofile']").forEach { elem ->
                val link = elem.attr("href")
                if (link.isNotBlank() && !shouldBlockUrl(link)) {
                    processPluginExtractor(link, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class M4uLinks : ExtractorApi() {
    override val name = "M4uLinks"
    override val mainUrl = "https://(?:m4ulinks|mdrive)\\..*"
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
                    href.contains("fastdlserver", true) ||
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

suspend fun processPluginExtractor(
    link: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (shouldBlockUrl(link)) return

    try {
        when {
            link.contains("fastdlserver", true) ->
                FastDLServer().getUrl(link, referer, subtitleCallback, callback)

            link.contains("fxlinks", true) ->
                FxLinks().getUrl(link, referer, subtitleCallback, callback)

            link.contains("m4ulinks", true) || link.contains("mdrive", true) ->
                M4uLinks().getUrl(link, referer, subtitleCallback, callback)

            link.contains("gofile", true) ->
                Gofile().getUrl(link, referer, subtitleCallback, callback)

            link.contains("hubcloud", true) || link.contains("gamerxyt", true) ->
                HubCloud().getUrl(link, referer, subtitleCallback, callback)

            link.contains("gdflix", true) || link.contains("gdlink", true) ->
                GDFlix().getUrl(link, referer, subtitleCallback, callback)

            link.contains("pixeldrain", true) ->
                Pixeldrain().getUrl(link, referer, subtitleCallback, callback)

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

open class Pixeldrain : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain\\.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        if (id.isBlank()) return
        val baseUrl = getBaseUrl(url).ifEmpty { "https://pixeldrain.com" }
        val streamUrl = "$baseUrl/api/file/$id?download"
        callback(
            newExtractorLink(name, name, streamUrl) {
                this.quality = Qualities.Unknown.value
                this.headers = VIDEO_HEADERS
            }
        )
    }
}

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile\\.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Gofile"
        try {
            val latestMainUrl = getLatestUrl(url, "gofile").ifEmpty { "https://gofile.io" }
            val latestApiUrl = latestMainUrl.replace("://", "://api.")

            val requestHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Origin" to latestMainUrl,
                "Referer" to "$latestMainUrl/",
            )
            val id = url.substringAfter("d/").substringBefore("/").substringBefore("?")
            if (id.isBlank()) return

            val genAccountRes = app.post("$latestApiUrl/accounts", headers = requestHeaders).text
            val jsonResp = JSONObject(genAccountRes)
            val token = jsonResp.optJSONObject("data")?.optString("token") ?: return

            val globalRes = app.get("$latestMainUrl/dist/js/config.js", headers = requestHeaders).text
            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

            val response = app.get(
                "$latestApiUrl/contents/$id?cache=true&sortField=createTime&sortDirection=1",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Origin" to latestMainUrl,
                    "Referer" to "$latestMainUrl/",
                    "Authorization" to "Bearer $token",
                    "X-Website-Token" to wt
                )
            ).text

            val jsonResponse = JSONObject(response)
            val data = jsonResponse.optJSONObject("data") ?: return
            val children = data.optJSONObject("children") ?: return
            val keys = children.keys()
            if (!keys.hasNext()) return
            val oId = keys.next()
            val child = children.optJSONObject(oId) ?: return
            val link = child.optString("link")
            val fileName = child.optString("name")
            val size = child.optLong("size", 0L)
            val formattedSize = if (size > 0) {
                if (size < 1024L * 1024 * 1024) {
                    "%.2f MB".format(size.toDouble() / (1024 * 1024))
                } else {
                    "%.2f GB".format(size.toDouble() / (1024 * 1024 * 1024))
                }
            } else ""

            if (link.isNotBlank() && link.startsWith("http")) {
                callback(
                    newExtractorLink("Gofile", "Gofile $fileName [$formattedSize]", link) {
                        this.quality = getIndexQuality(fileName)
                        this.headers = VIDEO_HEADERS + mapOf("Cookie" to "accountToken=$token")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Gofile error: ${e.message}")
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

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new3\\.gdflix\\.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDFlix"
        if (shouldBlockUrl(url)) return

        val latestUrl = getLatestUrl(url, "gdflix")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)

        Log.d(tag, "Processing: $newUrl")

        try {
            val document = app.get(newUrl).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").ifEmpty {
                document.selectFirst("h1, h2, h3, title")?.text() ?: ""
            }.trim()
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
            val baseQuality = getIndexQuality(fileName)

            document.select("div.text-center a, a.btn").forEach { anchor ->
                val text = anchor.text().trim()
                val href = anchor.attr("href")

                if (href.isBlank() || shouldBlockUrl(href)) return@forEach
                if (isInvalidLink(href, text)) return@forEach

                val score = getAdjustedQuality(baseQuality, fileSize, text, fileName)

                try {
                    when {
                        text.contains("Instant DL", true) || href.contains("instant.", true) -> {
                            val finalLink = try {
                                val resp = app.get(href, allowRedirects = false)
                                val loc = resp.headers["location"].orEmpty()
                                val target = if (loc.isNotEmpty()) loc else href
                                if (target.contains("url=")) {
                                    URLDecoder.decode(target.substringAfter("url="), StandardCharsets.UTF_8.toString())
                                } else {
                                    target
                                }
                            } catch (_: Exception) { href }

                            if (finalLink.isNotBlank() &&
                                !finalLink.contains("gamerxyt.com", true) &&
                                !finalLink.contains("/bgmi/", true)) {
                                callback(newExtractorLink(
                                    "GDFlix[Instant]", "GDFlix[Instant] $fileName [$fileSize]", finalLink
                                ) {
                                    this.quality = score + 800
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                        text.contains("FAST CLOUD", true) || href.contains("/cloud/", true) -> {
                            try {
                                val cloudDoc = app.get(href).document
                                val cloudAnchor = cloudDoc.selectFirst("a[href*='cloud-dl'], a[href*='workers.dev'], a.btn-success")
                                val cloudHref = cloudAnchor?.attr("href") ?: ""
                                if (cloudHref.isNotBlank()) {
                                    val cleanVideoUrl = cloudHref
                                        .replace(".mkv.zip", ".mkv")
                                        .replace(".mp4.zip", ".mp4")
                                        .replace(Regex("""\.zip(\?|$)"""), "$1")
                                    callback(newExtractorLink(
                                        "GDFlix[FastCloud]", "GDFlix[FastCloud] $fileName [$fileSize]", cleanVideoUrl
                                    ) {
                                        this.quality = score + 750
                                        this.headers = VIDEO_HEADERS
                                    })
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "FastCloud fetch error: ${e.message}")
                            }
                        }
                        (text.contains("CLOUD DOWNLOAD", true) || href.contains("r2.dev", true)) && !href.contains(".zip") -> {
                            val r2Link = if (href.contains("url="))
                                URLDecoder.decode(href.substringAfter("url="), "UTF-8") else href
                            if (r2Link.isNotBlank()) {
                                callback(newExtractorLink(
                                    "GDFlix[R2-Cloud]", "GDFlix[R2-Cloud] $fileName [$fileSize]", r2Link
                                ) {
                                    this.quality = score + 700
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                        text.contains("GOFILE", true) || href.contains("goflix", true) || href.contains("gofile", true) || href.contains("multiup", true) -> {
                            try {
                                val gofileUrl = if (href.contains("goflix.sbs") || href.contains("multiup")) {
                                    val mirrorDoc = app.get(href).document
                                    mirrorDoc.selectFirst("a[href*='gofile.io']")?.attr("href") ?: ""
                                } else href

                                if (gofileUrl.isNotBlank() && gofileUrl.contains("gofile.io")) {
                                    Gofile().getUrl(gofileUrl, referer, subtitleCallback, callback)
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Gofile mirror error: ${e.message}")
                            }
                        }
                        text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) || href.contains("indexserver", true) || href.contains("thunder.", true) -> {
                            val directLink = if (href.contains("indexserver") || href.contains("thunder.")) {
                                val cleanUrl = href.replace(Regex("""\.mkv$"""), "").replace(Regex("""\.mp4$"""), "")
                                href
                            } else href

                            callback(newExtractorLink(
                                "GDFlix[Direct]", "GDFlix[Direct] $fileName [$fileSize]", directLink
                            ) {
                                this.quality = score + 650
                                this.headers = VIDEO_HEADERS
                            })
                        }
                        href.contains("pixeldra", true) -> {
                            val pdUrl = if (href.contains("/u/"))
                                "${getBaseUrl(href)}/api/file/${href.substringAfterLast("/")}?download"
                            else href
                            callback(newExtractorLink(
                                "GDFlix[Pixeldrain]", "GDFlix[Pixeldrain] $fileName [$fileSize]", pdUrl
                            ) {
                                this.quality = score + 400
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Button error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing GDFlix: ${e.message}")
        }
    }
}
