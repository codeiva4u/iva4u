package com.skymovies

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
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

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
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
        "tpead.net"
    )
    return blockList.any { url.contains(it, ignoreCase = true) }
}

open class Howblogs : ExtractorApi() {
    override val name = "Howblogs"
    override val mainUrl = "https://(howblogs|linkstaker)\\..*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Howblogs"
        Log.d(tag, "Processing: $url")

        try {
            val doc = app.get(url).document
            val links = doc.select("a[href*='hubcloud'], a[href*='hubcdn'], a[href*='hubdrive'], a[href*='gdflix'], a[href*='pixeldrain'], a[href*='gofile.io'], a[href*='drivehub']")

            Log.d(tag, "Found ${links.size} links on howblogs page")

            links.amap { element ->
                val abs = element.absUrl("href")
                val href = if (abs.isNotBlank()) abs else element.attr("href")
                if (href.isBlank() || href.startsWith("#") || href.contains("t.me")) return@amap
                if (shouldBlockUrl(href)) return@amap

                try {
                    when {
                        href.contains("hubcloud", true) ->
                            HubCloud().getUrl(href, name, subtitleCallback, callback)
                        href.contains("gdflix", true) ->
                            GDFlix().getUrl(href, name, subtitleCallback, callback)
                        href.contains("hubcdn", true) ->
                            HUBCDN().getUrl(href, name, subtitleCallback, callback)
                        href.contains("pixeldrain", true) || href.contains("gofile.io", true) ->
                            loadExtractor(href, referer, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed inner link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing howblogs: ${e.message}")
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

            document.select("div.card-body a, a.btn").amap { element ->
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
                        text.contains("10Gbps", true) || text.contains("PixelServer", true) || (link.contains("pixel.hubcloud", true) && link.contains("?id=")) -> {
                            try {
                                val response = app.get(link, allowRedirects = true)
                                val finalUrl = response.url
                                val downloadUrl = if (finalUrl.contains("link=")) {
                                    URLDecoder.decode(finalUrl.substringAfter("link="), "UTF-8")
                                } else finalUrl
                                if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
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

        val allAnchors = document.select("div.text-center a")

        val fastServers = mutableListOf<org.jsoup.nodes.Element>()
        val slowServers = mutableListOf<org.jsoup.nodes.Element>()

        allAnchors.forEach { anchor ->
            val text = anchor.select("a").text()
            val href = anchor.attr("href")

            if (text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) || text.contains("ZIP", true) || href.contains(".zip", true)) {
                return@forEach
            }

            when {
                text.contains("Instant DL") || text.contains("DIRECT DL") || text.contains("DIRECT SERVER") || text.contains("CLOUD DOWNLOAD") || anchor.attr("href").contains("pixeldra") -> fastServers.add(anchor)
                else -> slowServers.add(anchor)
            }
        }

        for (anchor in fastServers) {
            val text = anchor.select("a").text()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)

            try {
                when {
                    text.contains("Instant DL") -> {
                        val instantDownloadLink = app.get(link, allowRedirects = false).headers["location"]?.substringAfter("url=").orEmpty()
                        if (instantDownloadLink.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink("GDFlix[Instant Download]", "GDFlix[Instant Download] $fileName[$fileSize]", instantDownloadLink) {
                                    this.quality = serverQuality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    }
                    text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> {
                        callback.invoke(
                            newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    text.contains("CLOUD DOWNLOAD [R2]") -> {
                        val cloudLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        callback.invoke(
                            newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", cloudLink) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    link.contains("pixeldra") -> {
                        val baseUrlLink = getBaseUrl(link)
                        val finalURL = if (link.contains("download", true)) link
                        else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                        callback.invoke(
                            newExtractorLink("Pixeldrain", "GDFlix[Pixeldrain] $fileName[$fileSize]", finalURL) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d("GDFlix Fast Server", e.toString())
            }
        }

        for (anchor in slowServers) {
            val text = anchor.select("a").text()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)

            try {
                when {
                    text.contains("Index Links") -> {
                        val indexDoc = app.get("$latestUrl$link").document
                        val firstServer = indexDoc.selectFirst("a.btn.btn-outline-info")
                        if (firstServer != null) {
                            val serverUrl = latestUrl + firstServer.attr("href")
                            val sourceAnchor = app.get(serverUrl).document.selectFirst("div.mb-4 > a")
                            if (sourceAnchor != null) {
                                val source = sourceAnchor.attr("href")
                                callback.invoke(
                                    newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                        this.quality = serverQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    }
                    text.contains("DRIVEBOT") -> {
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
                                callback.invoke(
                                    newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                        this.referer = driveBotBaseUrl
                                        this.quality = serverQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    }
                    text.contains("GoFile") -> {
                        val gofileAnchor = app.get(link).document.selectFirst(".row .row a")
                        if (gofileAnchor != null) {
                            val gofileLink = gofileAnchor.attr("href")
                            if (gofileLink.contains("gofile")) {
                                Gofile().getUrl(gofileLink, "", subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("GDFlix Slow Server", e.toString())
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
