package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Gofile
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

class GDFlixDev : GDFlix() {
    override var mainUrl = "https://gdflix.dev"
}


open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "gdflix")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = getIndexQuality(fileName)

        document.select("div.text-center a, .card-body a").amap { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")

            // Skip links that require login or are telegram links
            if (text.contains("Login To DL", ignoreCase = true) || 
                text.contains("Telegram", ignoreCase = true) ||
                link.contains("filesgram", ignoreCase = true) ||
                link.contains("/login", ignoreCase = true)) {
                return@amap
            }

            when {
                // Instant DL [10GBPS] - Direct link to busycdn
                text.contains("Instant DL", ignoreCase = true) -> {
                    try {
                        // The link itself is the direct video URL (busycdn.cfd)
                        if (link.contains("busycdn") || link.contains("instant.")) {
                            callback.invoke(
                                newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", link) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        } else {
                            // Fallback: follow redirect
                            val redirectLink = app.get(link, allowRedirects = false)
                                .headers["location"]?.substringAfter("url=").orEmpty()
                            if (redirectLink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", redirectLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }

                // FAST CLOUD / ZIPDISK - zfile links
                text.contains("FAST CLOUD", ignoreCase = true) || 
                text.contains("ZIPDISK", ignoreCase = true) ||
                link.contains("/zfile/") -> {
                    try {
                        val fullLink = if (link.startsWith("/")) "$latestUrl$link" else link
                        val zfileDoc = app.get(fullLink).document
                        
                        // Try to get direct video link from zfile page
                        val videoLink = zfileDoc.selectFirst("a.btn-success, a.btn-primary, a[href*='.mkv'], a[href*='.mp4']")
                            ?.attr("href").orEmpty()
                        
                        if (videoLink.isNotEmpty() && (videoLink.contains(".mkv") || videoLink.contains(".mp4") || 
                            videoLink.startsWith("http"))) {
                            callback.invoke(
                                newExtractorLink("GDFlix[FastCloud]", "GDFlix[FastCloud] $fileName[$fileSize]", videoLink) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        } else {
                            // If no direct link, use the zfile page itself
                            callback.invoke(
                                newExtractorLink("GDFlix[FastCloud]", "GDFlix[FastCloud] $fileName[$fileSize]", fullLink) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("FastCloud", e.toString())
                    }
                }

                // PixelDrain - both pixeldra.in and pixeldrain.dev
                link.contains("pixeldra", ignoreCase = true) -> {
                    try {
                        val baseUrlLink = getBaseUrl(link)
                        val fileId = link.substringAfterLast("/").substringBefore("?")
                        val finalURL = if (link.contains("download", true)) link
                        else if (link.contains("/u/")) "$baseUrlLink/api/file/$fileId?download"
                        else "$baseUrlLink/api/file/$fileId?download"
                        
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "GDFlix[Pixeldrain] $fileName[$fileSize]",
                                finalURL
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Pixeldrain", e.toString())
                    }
                }

                // GoFile [Ready] - goflix.sbs mirror page
                text.contains("GoFile", ignoreCase = true) -> {
                    try {
                        val mirrorDoc = app.get(link).document
                        
                        // Extract gofile.io links from the mirror page
                        mirrorDoc.select("a[href*='gofile.io'], a[href*='gofile.ee']").forEach { gofileAnchor ->
                            val gofileLink = gofileAnchor.attr("href")
                            if (gofileLink.contains("gofile")) {
                                Gofile().getUrl(gofileLink, "", subtitleCallback, callback)
                            }
                        }
                        
                        // Also try other file hosts from the mirror page
                        mirrorDoc.select("a.host[href], a[href*='megaup'], a[href*='1fichier']").forEach { hostAnchor ->
                            val hostLink = hostAnchor.attr("href")
                            val hostName = hostAnchor.select("img").attr("alt").ifEmpty { 
                                hostLink.substringAfter("://").substringBefore("/") 
                            }
                            if (hostLink.isNotEmpty() && !hostLink.contains("#")) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[$hostName]", "GDFlix[$hostName] $fileName[$fileSize]", hostLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }

                // DIRECT DL
                text.contains("DIRECT DL", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                // CLOUD DOWNLOAD [R2]
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) -> {
                    try {
                        val decodedLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        callback.invoke(
                            newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", decodedLink) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Cloud Download", e.toString())
                    }
                }

                // Index Links
                text.contains("Index Links", ignoreCase = true) -> {
                    try {
                        val fullLink = if (link.startsWith("/")) "$latestUrl$link" else link
                        app.get(fullLink).document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = if (btn.attr("href").startsWith("/")) 
                                    latestUrl + btn.attr("href") 
                                else btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val source = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                this.quality = quality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

                // DRIVEBOT
                text.contains("DRIVEBOT", ignoreCase = true) -> {
                    try {
                        val id = link.substringAfter("id=").substringBefore("&")
                        val doId = link.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://indexbot.site")

                        baseUrls.amap { drivebotBaseUrl ->
                            val indexbotLink = "$drivebotBaseUrl/download?id=$id&do=$doId"
                            val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                            if (indexbotResponse.isSuccessful) {
                                val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                val indexbotDoc = indexbotResponse.document

                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val requestBody = FormBody.Builder()
                                    .add("token", token)
                                    .build()

                                val headers = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$drivebotBaseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = headers,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                if (downloadLink.isNotEmpty()) {
                                    callback.invoke(
                                        newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                            this.referer = drivebotBaseUrl
                                            this.quality = quality
                                            this.headers = VIDEO_HEADERS
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                // Direct video file links
                (link.contains(".mkv") || link.contains(".mp4")) && !link.contains(".zip") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                else -> {
                    Log.d("GDFlix", "Unhandled link: $text -> $link")
                }
            }
        }
    }
}
