package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
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

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extract file ID from various Pixeldrain URL formats
            val mId = Regex("/(?:u|file|l)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
            
            if (mId.isNullOrEmpty()) {
                Log.d("PixelDrain", "Could not extract file ID from: $url")
                return
            }
            
            // Use direct API endpoint for better compatibility
            val finalUrl = "$mainUrl/api/file/$mId"
            
            // Get file info first to check if it's a valid video
            val infoResponse = app.get("$mainUrl/api/file/$mId/info", allowRedirects = false)
            if (infoResponse.isSuccessful) {
                val infoText = infoResponse.text
                val mimeType = Regex("\"mime_type\"\\s*:\\s*\"([^\"]+)\"").find(infoText)?.groupValues?.getOrNull(1)
                
                // Only process if it's a video file
                if (mimeType != null && mimeType.startsWith("video/")) {
                    callback.invoke(
                        newExtractorLink(this.name, this.name, finalUrl) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    Log.d("PixelDrain", "File is not a video. MIME type: $mimeType")
                }
            }
        } catch (e: Exception) {
            Log.e("PixelDrain", "Error extracting: ${e.message}")
        }
    }
}

class HubCloudInk : HubCloud() {
    override val mainUrl = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl = "https://hubcloud.art"
}

class HubCloudDad : HubCloud() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudBz : HubCloud() {
    override val mainUrl = "https://hubcloud.bz"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.one"
    override val requiresReferer = false

    fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newBaseUrl = "https://hubcloud.one"
        // Validate URL before processing
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("null", ignoreCase = true)) {
            Log.d("HubCloud", "Invalid URL skipped: $url")
            return // Invalid URL or null source, skip processing
        }
        val newUrl = url.replace(mainUrl, newBaseUrl)
        val doc = app.get(newUrl).document

        var link = if (newUrl.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.getOrNull(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if (link.startsWith("/")) {
            link = newBaseUrl + link
        }

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val div = document.selectFirst("div.card-body")

        div?.select("h2 a.btn")?.forEach {
            val btnLink = it.attr("href")
            val text = it.text()

            when {
                text.contains("Download [FSL Server]") -> {
                    callback.invoke(
                        newExtractorLink("$name[FSL Server]", "$name[FSL Server] $header[$size]", btnLink) {
                            quality = getIndexQuality(header)
                        }
                    )
                }

                text.contains("Download File") -> {
                    callback.invoke(
                        newExtractorLink(name, "$name $header[$size]", btnLink) {
                            quality = getIndexQuality(header)
                        }
                    )
                }

                text.contains("BuzzServer") -> {
                    val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                        .headers["hx-redirect"].orEmpty()
                    if (dlink.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "$name[BuzzServer]",
                                "$name[BuzzServer] $header[$size]",
                                getBaseUrl(btnLink) + dlink
                            ) {
                                quality = getIndexQuality(header)
                            }
                        )
                    }
                }

                btnLink.contains("pixeldra") -> {
                    try {
                        // Extract and use Pixeldrain properly
                        PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("HubCloud", "Pixeldrain extraction failed: ${e.message}")
                    }
                }

                text.contains("Download [Server : 10Gbps]") -> {
                    try {
                        // Follow the redirect chain to get final download link
                        val redirectResponse = app.get(btnLink, allowRedirects = false)
                        val locationHeader = redirectResponse.headers["location"] ?: return@forEach
                        
                        val dlink = if (locationHeader.contains("link=")) {
                            locationHeader.substringAfter("link=")
                        } else {
                            locationHeader
                        }
                        
                        // Validate final link
                        if (dlink.isBlank() || dlink.contains("null", ignoreCase = true)) {
                            Log.d("HubCloud", "Invalid 10Gbps link: $dlink")
                            return@forEach
                        }
                        
                        // Check if link is a valid video file or stream
                        if (dlink.matches(Regex(".*(mkv|mp4|avi|webm|m3u8).*", RegexOption.IGNORE_CASE))) {
                            callback.invoke(
                                newExtractorLink("$name[10Gbps]", "$name[10Gbps] $header[$size]", dlink) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        } else {
                            Log.d("HubCloud", "10Gbps link not a valid video file: $dlink")
                        }
                    } catch (e: Exception) {
                        Log.e("HubCloud", "Error processing 10Gbps link: ${e.message}")
                    }
                }

                else -> {
                    try {
                        loadExtractor(btnLink, "", subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("HubCloud", "LoadExtractor Error: ${e.localizedMessage}")
                    }
                }
            }
        }
    }
}

open class fastdlserver : ExtractorApi() {
    override val name: String = "fastdlserver"
    override var mainUrl = "https://fastdlserver.lol"
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
    override var mainUrl = "https://gdlink.dev"
}

class GDFlix3 : GDFlix() {
    override var mainUrl = "https://new4.gdflix.dad"
}

class GDFlix2 : GDFlix() {
    override var mainUrl = "https://new.gdflix.dad"
}

class GDFlix7 : GDFlix() {
    override var mainUrl = "https://gdflix.dad"
}

class GDFlixDev : GDFlix() {
    override var mainUrl = "https://gdflix.dev"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new6.gdflix.dad"
    override val requiresReferer = false

    private suspend fun getLatestUrl(): String {
        return try {
            val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
            val url = JSONObject(response).optString("gdflix")
            if (url.isNullOrEmpty() || !url.startsWith("http")) {
                return mainUrl
            }
            url
        } catch (e: Exception) {
            // If GitHub fetch fails, return the hardcoded mainUrl
            mainUrl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl()
        val newUrl = url.replace(mainUrl, latestUrl)
        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()

            when {
                text.contains("DIRECT DL") -> {
                    val link = anchor.attr("href")
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    val link = anchor.attr("href")
                    callback.invoke(
                        newExtractorLink("GDFlix[Cloud Download]", "GDFlix[Cloud Download] $fileName[$fileSize]", link) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("PixelDrain DL") -> {
                    try {
                        val link = anchor.attr("href")
                        // Use PixelDrain extractor for proper handling
                        PixelDrain().getUrl(link, latestUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("GDFlix", "PixelDrain extraction failed: ${e.message}")
                    }
                }

                text.contains("Index Links") -> {
                    try {
                        val link = anchor.attr("href")
                        app.get("$latestUrl$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = latestUrl + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val source = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                this.quality = getIndexQuality(fileName)
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
                        val driveLink = anchor.attr("href")
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

                                var downloadLink = app.post(
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
                                        this.quality = getIndexQuality(fileName)
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
                        val instantLink = anchor.attr("href")
                        val link = app.get(instantLink, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        callback.invoke(
                            newExtractorLink("GDFlix[Instant Download]", "GDFlix[Instant Download] $fileName[$fileSize]", link) {
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }
                text.contains("GoFile") -> {
                    try {
                        val gofileMirrorUrl = anchor.attr("href")
                        
                        // Handle goflix.sbs mirror and direct gofile links
                        if (gofileMirrorUrl.contains("goflix.sbs", ignoreCase = true)) {
                            // Extract from goflix.sbs mirror page
                            val mirrorDoc = app.get(gofileMirrorUrl).document
                            mirrorDoc.select(".row .row a, a[href*='gofile']").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile", ignoreCase = true)) {
                                    Gofile().getUrl(link, latestUrl, subtitleCallback, callback)
                                }
                            }
                        } else if (gofileMirrorUrl.contains("gofile", ignoreCase = true)) {
                            // Direct gofile link
                            Gofile().getUrl(gofileMirrorUrl, latestUrl, subtitleCallback, callback)
                        } else {
                            // Try to find gofile links in the page
                            app.get(gofileMirrorUrl).document
                                .select(".row .row a, a[href*='gofile']").amap { gofileAnchor ->
                                    val link = gofileAnchor.attr("href")
                                    if (link.contains("gofile", ignoreCase = true)) {
                                        Gofile().getUrl(link, latestUrl, subtitleCallback, callback)
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        Log.e("Gofile", "Error extracting GoFile: ${e.message}")
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
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Referer" to mainUrl,
        )
        //val res = app.get(url)
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val genAccountRes = app.post("$mainApi/accounts", headers = headers).text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token") ?: return

        val globalRes = app.get("$mainUrl/dist/js/global.js", headers = headers).text
        val wt = Regex("""appdata\.wt\s*=\s*[\"']([^\"']+)[\"']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?wt=$wt",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
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

        callback.invoke(
            newExtractorLink(
                "Gofile",
                "Gofile $fileName[$formattedSize]",
                link,
            ) {
                this.quality = getQuality(fileName)
                this.headers = mapOf(
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