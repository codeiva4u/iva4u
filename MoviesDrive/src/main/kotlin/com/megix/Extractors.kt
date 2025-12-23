package com.megix

import android.util.Log
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
        val baseUrl = getBaseUrl(url)
        val doc = app.get(url).document

        // HubCloud /drive/ URLs need to follow specific redirects
        var link = if(url.contains("/drive/")) {
            // Find "Generate Direct Download Link" or similar
            // Prioritize looking for the specific link structure that leads to the download page
            val generateLink = doc.selectFirst("a:contains(Generate Direct Download Link)")?.attr("href")
                ?: doc.selectFirst("a[href*='gamerxyt']")?.attr("href")
                ?: doc.selectFirst("a[href*='carnewz']")?.attr("href")
                ?: doc.selectFirst("a.btn-success")?.attr("href") // Fallback for generic button

            if (!generateLink.isNullOrEmpty()) {
                // Follow the link to the page with actual download buttons
                val absoluteGenerateLink = if (generateLink.startsWith("http")) generateLink else baseUrl + generateLink
                val gamerDoc = app.get(absoluteGenerateLink).document
                processGamerxytPage(gamerDoc, callback)
                return
            }
            // Fallback: try old script method if button missing
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        }
        else if(url.contains("/file/")) {
            // Direct download page for /file/ URLs
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }
        else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if(link.isEmpty()) return

        if(!link.startsWith("https://")) {
            link = baseUrl + link
        }

        // Proceed to extract from the resolved link (old logic fallback)
        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        div?.select("h2 a.btn")?.amap {
            val link = it.attr("href")
            val text = it.text()

            if (text.contains("[FSL Server]")) {
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
            } else if (text.contains("[FSLv2 Server]")) {
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
            } else if (text.contains("[Mega Server]")) {
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
            } else if (text.contains("Download File")) {
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
            } else if(text.contains("BuzzServer")) {
                val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                val baseUrl = getBaseUrl(link)
                if(dlink != "") {
                    callback.invoke(
                        newExtractorLink(
                            "$name[BuzzServer]",
                            "$name[BuzzServer] $header[$size]",
                            baseUrl + dlink,
                        ) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            } else if (link.contains("pixeldra")) {
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
            } else if (text.contains("Download [Server : 10Gbps]")) {
                val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
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
            } else {
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

    // Process the intermediate page (e.g. gamerxyt, carnewz) to find download links
    private suspend fun processGamerxytPage(doc: org.jsoup.nodes.Document, callback: (ExtractorLink) -> Unit) {
        val header = doc.select("div.card-header").text().ifEmpty { "Video" }
        val size = doc.select("i#size").text().ifEmpty { "" }
        val quality = getIndexQuality(header)
        val sizeText = if(size.isNotEmpty()) "[$size]" else ""

        // Extract links from all buttons
        doc.select("a.btn").forEach { btn ->
            val link = btn.attr("href")
            val text = btn.text()

            when {
                // FSL Server - Direct download or via r2.dev
                text.contains("FSL Server", ignoreCase = true) -> {
                    // Check if it's already a direct link or needs following
                   if (link.contains("r2.dev") || link.contains("worker.dev")) {
                        callback.invoke(
                            newExtractorLink(
                                "$name[FSL Server]",
                                "$name[FSL Server] $header$sizeText",
                                link
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } else {
                         // Sometimes it's a relative link or needs one hop
                         try {
                             val resolvedLink = if(link.startsWith("http")) link else getBaseUrl(doc.baseUri()) + link
                             if(resolvedLink.contains(".mkv") || resolvedLink.contains(".mp4")) {
                                 callback.invoke(
                                    newExtractorLink(
                                        "$name[FSL Server]",
                                        "$name[FSL Server] $header$sizeText",
                                        resolvedLink
                                    ) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                             }
                         } catch(_: Exception) {}
                    }
                }
                // 10Gbps Server - often redirects through pixel/hubcdn/workers
                text.contains("10Gbps", ignoreCase = true) -> {
                    try {
                        // Follow redirect chain
                        // pixel.hubcdn.fans -> pixel.rohitkiskk.workers.dev -> ...
                        var currentLink = link
                        var tries = 0
                        // Follow up to 5 redirects manually to be safe
                        while(tries < 5) {
                            val next = app.get(currentLink, allowRedirects = false).headers["location"]
                            if (next.isNullOrEmpty()) break
                            currentLink = next
                            if (currentLink.contains("link=")) break
                            tries++
                        }
                        
                        val finalUrl = currentLink

                        if (finalUrl.contains("link=")) {
                            val videoUrl = finalUrl.substringAfter("link=").substringBefore("&")
                            val decodedUrl = URLDecoder.decode(videoUrl, "UTF-8")
                            if (decodedUrl.isNotEmpty() && decodedUrl.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[10Gbps]",
                                        "$name[10Gbps] $header$sizeText",
                                        decodedUrl
                                    ) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) { }
                }
                // Pixeldrain
                text.contains("Pixel", ignoreCase = true) && link.contains("pixeldrain") -> {
                    try {
                        val pixelId = link.substringAfterLast("/")
                        val finalUrl = "https://pixeldrain.com/api/file/$pixelId?download"
                        callback.invoke(
                            newExtractorLink(
                                "$name[Pixeldrain]",
                                "$name[Pixeldrain] $header$sizeText",
                                finalUrl
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    } catch (_: Exception) { }
                }
                // Fallback for direct links
                !link.contains(".zip") && (link.contains(".mkv") || link.contains(".mp4")) -> {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header$sizeText",
                            link
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
    // Updated regex to support new10 and potential future numbers
    override var mainUrl = "https://new\\d*.gdflix.*"
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
        val baseUrl = getBaseUrl(url)
        val document = app.get(url).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = getIndexQuality(fileName)

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                // Instant DL [10GBPS] - handles busycdn.cfd and similar redirects
                (text.contains("Instant DL", ignoreCase = true)) -> {
                    try {
                        // Check if it's the busycdn link which redirects to fastcdn
                        if (link.contains("busycdn") || link.contains("instant")) {
                            val redirectLink = app.get(link, allowRedirects = false).headers["location"]
                            val finalLink = if (redirectLink != null && redirectLink.contains("url=")) {
                                // Extract url parameter from e.g. https://fastcdn-dl.pages.dev/?url=...
                                val rawUrl = redirectLink.substringAfter("url=").substringBefore("&")
                                URLDecoder.decode(rawUrl, "UTF-8")
                            } else {
                                link
                            }

                            if (finalLink.isNotEmpty() && finalLink.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[10GBPS]", "GDFlix[10GBPS] $fileName[$fileSize]", finalLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } else {
                            // Try generic redirect handling for Instant DL if not busycdn specifically
                             val redirectLink = app.get(link, allowRedirects = false).headers["location"]
                             if(!redirectLink.isNullOrEmpty()){
                                 val finalLink = if(redirectLink.contains("url=")) redirectLink.substringAfter("url=") else redirectLink
                                 callback.invoke(
                                    newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", finalLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                             }
                        }
                    } catch (_: Exception) {
                        Log.d("Instant DL", "Error extracting Instant DL link")
                    }
                }

                // DIRECT SERVER [MGT] - cloudbox.lol
                (text.contains("DIRECT SERVER", ignoreCase = true) || link.contains("cloudbox")) -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[MGT]", "GDFlix[MGT] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("DIRECT DL") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    val decodedLink = if(link.contains("url=")) URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString()) else link
                    callback.invoke(
                        newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", decodedLink) {
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
                        app.get("$baseUrl$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = baseUrl + btn.attr("href")
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
                        val driveLink = link
                        val id = driveLink.substringAfter("id=").substringBefore("&")
                        val doId = driveLink.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://indexbot.site")

                        baseUrls.amap { baseUrl ->
                            val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
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
                                    "$baseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = headers,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                callback.invoke(
                                    newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                        this.referer = baseUrl
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
                text.contains("GoFile") -> {
                    try {
                        app.get(link).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Gofile().getUrl(link, "", subtitleCallback, callback)
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
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?wt=$wt",
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

        if(link != null) {
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

class FastLinks : ExtractorApi() {
    override val name: String = "FastLinks"
    override val mainUrl: String = "https://fastilinks.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url)
        val ssid = res.cookies["PHPSESSID"].toString()
        val cookies = mapOf("PHPSESSID" to ssid)
        val formBody = FormBody.Builder()
            .add("_csrf_token_645a83a41868941e4692aa31e7235f2", "3000f5248d9d207e4941e0aa053e1bcfd04dcbab")
            .build()

        val doc = app.post(
            url,
            requestBody = formBody,
            cookies = cookies
        ).document
        doc.select("div.well > a"). amap { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }
    }
}