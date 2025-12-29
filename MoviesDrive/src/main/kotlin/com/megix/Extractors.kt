package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
    if (link.isNullOrEmpty()) {
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
        var link = if (newUrl.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if (!link.startsWith("https://")) {
            link = latestUrl + link
        }

        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        div?.select("h2 a.btn")?.amap {
            val btnLink = it.attr("href")
            val text = it.text()

            if (text.contains("[FSL Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSL Server]",
                        "$name[FSL Server] $header[$size]",
                        btnLink,
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
                        btnLink,
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
                        btnLink,
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
                        btnLink,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false).headers["hx-redirect"] ?: ""
                val buzzBaseUrl = getBaseUrl(btnLink)
                if (dlink.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            "$name[BuzzServer]",
                            "$name[BuzzServer] $header[$size]",
                            buzzBaseUrl + dlink,
                        ) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }

            else if (btnLink.contains("pixeldra")) {
                val pixelBaseUrl = getBaseUrl(btnLink)
                val finalURL = if (btnLink.contains("download", true)) btnLink
                else "$pixelBaseUrl/api/file/${btnLink.substringAfterLast("/")}?download"

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
            else if (text.contains("Download [Server : 10Gbps]")) {
                val dlink = app.get(btnLink, allowRedirects = false).headers["location"] ?: ""
                callback.invoke(
                    newExtractorLink(
                        "$name[Download]",
                        "$name[Download] $header[$size]",
                        dlink.substringAfter("link="),
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else
            {
                if (!btnLink.contains(".zip") && (btnLink.contains(".mkv") || btnLink.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header[$size]",
                            btnLink,
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

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink.*"
}
class GDFlixNet : GDFlix() {
    override var mainUrl = "https://new10.gdflix.*"
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

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                text.contains("DIRECT DL") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("DIRECT SERVER") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    val cloudLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                    callback.invoke(
                        newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", cloudLink) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                link.contains("pixeldra") -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
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
                }

                text.contains("Index Links") -> {
                    try {
                        app.get("$latestUrl$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = latestUrl + btn.attr("href")
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

                text.contains("DRIVEBOT") -> {
                    try {
                        val id = link.substringAfter("id=").substringBefore("&")
                        val doId = link.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://indexbot.site")

                        baseUrls.amap { driveBotBaseUrl ->
                            val indexbotLink = "$driveBotBaseUrl/download?id=$id&do=$doId"
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

                                val postHeaders = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$driveBotBaseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = postHeaders,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                callback.invoke(
                                    newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                        this.referer = driveBotBaseUrl
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                text.contains("Instant DL") -> {
                    try {
                        val instantDownloadLink = app.get(link, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        callback.invoke(
                            newExtractorLink("GDFlix[Instant Download]", "GDFlix[Instant Download] $fileName[$fileSize]", instantDownloadLink) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }
                text.contains("GoFile") -> {
                    try {
                        app.get(link).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val gofileLink = gofileAnchor.attr("href")
                                if (gofileLink.contains("gofile")) {
                                    Gofile().getUrl(gofileLink, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }

                else -> {
                    Log.d("Error", "No Server matched")
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

    @Suppress("UNUSED_PARAMETER")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to mainUrl,
            "Referer" to mainUrl,
        )
        val id = url.substringAfter("d/").substringBefore("/")
        val genAccountRes = app.post("$mainApi/accounts", headers = requestHeaders).text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token")
        val globalRes = app.get("$mainUrl/dist/js/config.js", headers = requestHeaders).text
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?cache=true&sortField=createTime&sortDirection=1",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Origin" to mainUrl,
                "Referer" to mainUrl,
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
            val sizeInMB = size.toDouble() / (1024 * 1024)
            "%.2f MB".format(sizeInMB)
        } else {
            val sizeInGB = size.toDouble() / (1024 * 1024 * 1024)
            "%.2f GB".format(sizeInGB)
        }

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

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}