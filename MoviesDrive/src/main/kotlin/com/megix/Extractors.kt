package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

suspend fun getLatestUrl(url: String, source: String): String {
    val link = JSONObject(
        app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
    ).optString(source)
    if(link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "hubcloud")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        val doc = app.get(newUrl).document
        var link = if(newUrl.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
        }
        else {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }

        if(!link.startsWith("https://")) {
            link = latestUrl + link
        }

        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        div?.select("h2 a.btn")?.amap {
            val link = it.attr("href")
            val text = it.text()

            if (text.contains("[FSL Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSL Server]",
                        "$name[FSL Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("[FSLv2 Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSLv2 Server]",
                        "$name[FSLv2 Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("[Mega Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[Mega Server]",
                        "$name[Mega Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("Download File")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (link.contains("pixeldra")) {
                val baseUrlLink = getBaseUrl(link)
                val finalURL = if (link.contains("download", true)) link
                else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                callback.invoke(
                    newExtractorLink(
                        "Pixeldrain",
                        "Pixeldrain $header[$size]",
                        finalURL,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else
            {
                if(!link.contains(".zip") && (link.contains(".mkv") || link.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header[$size]",
                            link,
                        ) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
        }
    }
}

open class fastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink.*"
}

class GDFlixNet : GDFlix() {
    override var mainUrl = "https://new9.gdflix.*"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    // Regex Patterns - सर्वर और URL identification के लिए
    companion object {
        // Server button text identify करने के लिए Regex patterns
        private val INSTANT_DL_REGEX = Regex("""Instant\s*DL.*?10\s*GBPS""", RegexOption.IGNORE_CASE)
        private val FAST_CLOUD_REGEX = Regex("""(FAST\s*CLOUD|ZIPDISK)""", RegexOption.IGNORE_CASE)
        private val GOFILE_REGEX = Regex("""GoFile""", RegexOption.IGNORE_CASE)
        private val PIXELDRAIN_REGEX = Regex("""pixeldrain\.(dev|com|io)/u/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
        
        // URL से video link extract करने के लिए Regex patterns
        private val VIDEO_URL_REGEX = Regex("""[?&]url=([^&\s]+)""")
        private val GOOGLE_DRIVE_REGEX = Regex("""(https?://[^\s"']+googleusercontent\.com[^\s"']+)""")
        
        // File hosting sites identify करने के लिए Regex
        private val HOST_PATTERN_REGEX = Regex("""(megaup\.net|vikingfile\.com|1fichier\.com|gofile\.io)""", RegexOption.IGNORE_CASE)
        
        // File ID extract करने के लिए patterns
        private val PIXELDRAIN_ID_REGEX = Regex("""/u/([a-zA-Z0-9]+)""")
        private val ZFILE_PATH_REGEX = Regex("""/zfile/(\d+)/([a-zA-Z0-9]+)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // GDFlix का latest URL प्राप्त करना
        val latestUrl = getLatestUrl(url, "gdflix")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        val document = app.get(newUrl).document

        // File name और size extract करना - नए selector के साथ
        val listItems = document.select("ul.list-group li.list-group-item")
        val fileName = listItems.getOrNull(0)?.text()?.substringAfter("Name : ").orEmpty()
        val fileSize = listItems.getOrNull(2)?.text()?.substringAfter("Size : ").orEmpty()
        val quality = getIndexQuality(fileName)

        // सभी download buttons को select करना - नया selector: a.btn
        document.select("a.btn").amap { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")

            when {
                // Instant DL [10GBPS] - Regex से identify करना और redirect follow करके Google Drive URL extract करना
                INSTANT_DL_REGEX.containsMatchIn(text) -> {
                    try {
                        // instant.busycdn.cfd से redirect follow करना
                        val response = app.get(link, allowRedirects = false)
                        val redirectUrl = response.headers["location"].orEmpty()
                        
                        // Regex से video URL extract करना
                        val videoUrl = VIDEO_URL_REGEX.find(redirectUrl)?.groupValues?.get(1)
                            ?: GOOGLE_DRIVE_REGEX.find(redirectUrl)?.groupValues?.get(1)
                            ?: redirectUrl.substringAfter("?url=").takeIf { it.isNotEmpty() }
                            ?: redirectUrl
                        
                        if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    "GDFlix[Instant 10GBPS]",
                                    "GDFlix[Instant 10GBPS] $fileName[$fileSize]",
                                    videoUrl
                                ) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("Instant DL 10GBPS", e.toString())
                    }
                }

                // FAST CLOUD / ZIPDISK - Regex से identify करना और zfile page से download link निकालना
                FAST_CLOUD_REGEX.containsMatchIn(text) -> {
                    try {
                        val zfileUrl = if (link.startsWith("http")) link else "$latestUrl$link"
                        
                        // Regex से zfile path verify करना
                        if (ZFILE_PATH_REGEX.containsMatchIn(zfileUrl) || link.contains("/zfile/")) {
                            val zfileDoc = app.get(zfileUrl).document
                            
                            // Cloud Resume Download button से link निकालना
                            val downloadLink = zfileDoc.select("a.btn").firstOrNull()?.attr("href").orEmpty()
                            
                            if (downloadLink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "GDFlix[FastCloud]",
                                        "GDFlix[FastCloud] $fileName[$fileSize]",
                                        downloadLink
                                    ) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("FastCloud", e.toString())
                    }
                }

                // PixelDrain - Regex से link और file ID extract करना
                PIXELDRAIN_REGEX.containsMatchIn(link) -> {
                    try {
                        val pixeldrainBase = getBaseUrl(link)
                        // Regex से file ID extract करना
                        val fileId = PIXELDRAIN_ID_REGEX.find(link)?.groupValues?.get(1)
                            ?: link.substringAfterLast("/")
                        val downloadUrl = "$pixeldrainBase/api/file/$fileId?download"
                        
                        callback.invoke(
                            newExtractorLink(
                                "GDFlix[Pixeldrain]",
                                "GDFlix[Pixeldrain] $fileName[$fileSize]",
                                downloadUrl
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Pixeldrain", e.toString())
                    }
                }

                // GoFile [Ready] - Regex से identify करना और goflix.sbs/en/mirror/{id} page से links निकालना
                GOFILE_REGEX.containsMatchIn(text) -> {
                    try {
                        val mirrorDoc = app.get(link).document
                        
                        // Download links with external hosts निकालना
                        mirrorDoc.select("a[target=_blank]").amap { hostLink ->
                            val hostUrl = hostLink.attr("href")
                            
                            // Regex से host pattern match करना
                            val hostMatch = HOST_PATTERN_REGEX.find(hostUrl)
                            if (hostMatch != null) {
                                val hostName = when {
                                    hostUrl.contains("megaup", ignoreCase = true) -> "MegaUp"
                                    hostUrl.contains("vikingfile", ignoreCase = true) -> "VikingFile"
                                    hostUrl.contains("1fichier", ignoreCase = true) -> "1Fichier"
                                    hostUrl.contains("gofile", ignoreCase = true) -> "GoFile"
                                    else -> "Unknown"
                                }
                                
                                callback.invoke(
                                    newExtractorLink(
                                        "GDFlix[$hostName]",
                                        "GDFlix[$hostName] $fileName[$fileSize]",
                                        hostUrl
                                    ) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("GoFile", e.toString())
                    }
                }

                else -> {
                    // Log unmatched servers for debugging
                    if (text.isNotEmpty() && !text.contains("Login") && !text.contains("Telegram")) {
                        Log.d("GDFlix", "Unmatched server: $text -> $link")
                    }
                }
            }
        }

    }

    class Gofile : ExtractorApi() {
        override val name = "Gofile"
        override val mainUrl = "https://gofile.io"
        override val requiresReferer = false
        private val mainApi = "https://api.gofile.io"

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Origin" to mainUrl,
                "Referer" to mainUrl,
            )
            val id = url.substringAfter("d/").substringBefore("/")
            val genAccountRes = app.post("$mainApi/accounts", headers = headers).text
            val jsonResp = JSONObject(genAccountRes)
            val token = jsonResp.getJSONObject("data").getString("token") ?: return

            val globalRes = app.get("$mainUrl/dist/js/global.js", headers = headers).text
            val wt =
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(
                    1
                ) ?: return

            val response = app.get(
                "$mainApi/contents/$id?wt=$wt",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Referer" to mainUrl,
                    "Authorization" to "Bearer $token",
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
                val sizeInMB = size.toDouble() / (1024 * 1024)
                "%.2f MB".format(sizeInMB)
            } else {
                val sizeInGB = size.toDouble() / (1024 * 1024 * 1024)
                "%.2f GB".format(sizeInGB)
            }

            if (link != null) {
                callback.invoke(
                    newExtractorLink(
                        "Gofile",
                        "Gofile $fileName[$formattedSize]",
                        link,
                    ) {
                        this.quality = getQuality(fileName)
                        this.headers = VIDEO_HEADERS + mapOf(
                            "Cookie" to "accountToken=$token"
                        )
                    }
                )
            }
        }

        private fun getQuality(str: String?): Int {
            return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
        }
    }
}