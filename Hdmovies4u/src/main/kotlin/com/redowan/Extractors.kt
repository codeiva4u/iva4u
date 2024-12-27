package com.redowan

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.json.JSONObject

open class Drivetot : ExtractorApi() {
    override val name: String = "Drivetot"
    override val mainUrl: String = "https://drivetot.dad"
    override val requiresReferer = false

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun CFType1(url: String): List<String> {
        val document = app.get(url+"?type=1").document
        val links = document.select("a.btn-success").mapNotNull { it.attr("href") }
        return links
    }

    private suspend fun resumeCloudLink(url: String): String {
        val resumeCloudUrl = mainUrl + url
        val document = app.get(resumeCloudUrl).document
        val link = document.selectFirst("a.btn-success")?.attr("href").toString()
        return link
    }

    private suspend fun resumeBot(url : String): String {
        val resumeBotResponse = app.get(url)
        val resumeBotDoc = resumeBotResponse.document.toString()
        val ssid = resumeBotResponse.cookies["PHPSESSID"]
        val resumeBotToken = Regex("formData\\.append\\('token', '([a-f0-9]+)'\\)").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotPath = Regex("fetch\\('/download\\?id=([a-zA-Z0-9/+]+)'").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotBaseUrl = url.split("/download")[0]
        val requestBody = FormBody.Builder()
            .addEncoded("token", "$resumeBotToken")
            .build()

        val jsonResponse = app.post(resumeBotBaseUrl + "/download?id=" + resumeBotPath,
            requestBody = requestBody,
            headers = mapOf(
                "Accept" to "*/*",
                "Origin" to resumeBotBaseUrl,
                "Sec-Fetch-Site" to "same-origin"
            ),
            cookies = mapOf("PHPSESSID" to "$ssid"),
            referer = url
        ).text
        val jsonObject = JSONObject(jsonResponse)
        val link = jsonObject.getString("url")
        return link
    }

    private suspend fun instantLink(finallink: String): String {
        val url = if(finallink.contains("video-leech")) "video-leech.xyz" else "video-seed.xyz"
        val token = finallink.substringAfter("https://$url/?url=")
        val downloadlink = app.post(
            url = "https://$url/api",
            data = mapOf(
                "keys" to token
            ),
            referer = finallink,
            headers = mapOf(
                "x-token" to url,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
            )
        )
        val finaldownloadlink =
            downloadlink.toString().substringAfter("url\":\"")
                .substringBefore("\",\"name")
                .replace("\\/", "/")
        val link = finaldownloadlink
        return link
    }


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = if(url.contains("r?key=")) {
            val temp = app.get(url).document.selectFirst("script")?.data()?.substringAfter("replace(\"")?.substringBefore("\")") ?: ""
            app.get(mainUrl + temp).document
        }
        else {
            app.get(url).document
        }
        val quality = document.selectFirst("li.list-group-item")?.text() ?: ""
        val fileName = quality.replace("Name : ", "")
        document.select("div.text-center > a").amap { element ->
            val text = element.text()
            val href = element.attr("href")
            when {
                text.contains("Instant Download") -> {
                    val instant = instantLink(href)
                    callback.invoke(
                        ExtractorLink(
                            "$name Instant(Download)",
                            "$name Instant(Download) - $fileName",
                            instant,
                            "",
                            getIndexQuality(quality)
                        )
                    )
                }
                text.contains("Resume Worker Bot") -> {
                    val resumeLink = resumeBot(href)
                    callback.invoke(
                        ExtractorLink(
                            "$name ResumeBot(VLC)",
                            "$name ResumeBot(VLC) - $fileName",
                            resumeLink,
                            "",
                            getIndexQuality(quality)
                        )
                    )
                }
                text.contains("Direct Links") -> {
                    val link = mainUrl + href
                    CFType1(link).forEach {
                        callback.invoke(
                            ExtractorLink(
                                "$name CF Type1",
                                "$name CF Type1 - $fileName",
                                it,
                                "",
                                getIndexQuality(quality)
                            )
                        )
                    }
                }
                text.contains("Resume Cloud") -> {
                    val resumeCloud = resumeCloudLink(href)
                    callback.invoke(
                        ExtractorLink(
                            "$name ResumeCloud",
                            "$name ResumeCloud - $fileName",
                            resumeCloud,
                            "",
                            getIndexQuality(quality)
                        )
                    )
                }
                else -> {
                }
            }
        }
    }
}

class PixelDrain : ExtractorApi() {
    override val name            = "PixelDrain"
    override val mainUrl         = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty())
        {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
        else {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    "$mainUrl/api/file/${mId}?download",
                    url,
                    Qualities.Unknown.value,
                )
            )
        }
    }
}

class HubCloudClub : HubCloud() {
    override val mainUrl: String = "https://hubcloud.club"
}

class HubCloudlol : HubCloud() {
    override var mainUrl = "https://hubcloud.lol"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.art"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = app.get(url).text
        val newLink = text.substringAfter("url=").substringBefore("\"")
        val newDoc = app.get(newLink).document
        var gamerLink : String

        if(newLink.contains("drive")) {
            val scriptTag = newDoc.selectFirst("script:containsData(url)")?.toString() ?: ""
            gamerLink = Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
        }

        else {
            gamerLink = newDoc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }

        val document = app.get(gamerLink).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text() ?: ""
        div?.select("h2 a.btn")?.apmap {
            val link = it.attr("href")

            if (link.contains("technorozen.workers.dev"))
            {
                val iframe = getGBurl(link)
                callback.invoke(
                    ExtractorLink(
                        "$name[Download]",
                        "$name[Download] - $header",
                        iframe,
                        "",
                        getIndexQuality(header),
                    )
                )
            }
            else if (link.contains("pixeldra.in")) {
                callback.invoke(
                    ExtractorLink(
                        "Pixeldrain",
                        "Pixeldrain - $header",
                        link,
                        "",
                        getIndexQuality(header),
                    )
                )
            }
            else if (link.contains(".dev")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name - $header",
                        link,
                        "",
                        getIndexQuality(header),
                    )
                )
            }
            else if (link.contains("fastdl.lol"))
            {
                callback.invoke(
                    ExtractorLink(
                        "$name[Download]",
                        "$name[Download] - $header",
                        link,
                        "",
                        getIndexQuality(header),
                    )
                )
            }
            else if (link.contains("hubcdn.xyz"))
            {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name - $header",
                        link,
                        "",
                        getIndexQuality(header),
                    )
                )
            }
            else if (link.contains("gofile.io"))
            {
                loadExtractor(link,"",subtitleCallback, callback)
            }
            else if (link.contains("pixeldrain"))
            {
                loadExtractor(link,"",subtitleCallback, callback)
            }
            else
            {
                //Nothing
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun getGBurl(url: String): String {
        return app.get(url).document.selectFirst("#vd")?.attr("href") ?:""
    }

}


open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://new6.gdflix.cfd"
    override val requiresReferer = false

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val fileName = document.selectFirst("ul > li.list-group-item")?.text()?.substringAfter("Name : ") ?: ""
        document.select("div.text-center a").amap {
            val text = it.select("a").text()
            if (
                text.contains("FAST CLOUD") &&
                !text.contains("ZIP")
            )
            {
                val link=it.attr("href")
                if(link.contains("mkv") || link.contains("mp4")) {
                    callback.invoke(
                        ExtractorLink(
                            "GDFlix[Fast Cloud]",
                            "GDFLix[Fast Cloud] - $fileName",
                            link,
                            "",
                            getIndexQuality(fileName),
                        )
                    )
                }
                else {
                    val trueurl=app.get("https://new6.gdflix.cfd$link", timeout = 100L).document.selectFirst("a.btn-success")?.attr("href") ?:""
                    callback.invoke(
                        ExtractorLink(
                            "GDFlix[Fast Cloud]",
                            "GDFLix[Fast Cloud] - $fileName",
                            trueurl,
                            "",
                            getIndexQuality(fileName)
                        )
                    )
                }
            }
            else if(text.contains("DIRECT DL")) {
                val link = it.attr("href")
                callback.invoke(
                    ExtractorLink(
                        "GDFlix[Direct]",
                        "GDFLix[Direct] - $fileName",
                        link,
                        "",
                        getIndexQuality(fileName),
                    )
                )
            }
            else if(text.contains("Index Links")) {
                val link = it.attr("href")
                val doc = app.get("https://new6.gdflix.cfd$link").document
                doc.select("a.btn.btn-outline-info").amap {
                    val serverUrl = mainUrl + it.attr("href")
                    app.get(serverUrl).document.select("div.mb-4 > a").amap {
                        val source = it.attr("href")
                        callback.invoke(
                            ExtractorLink(
                                "GDFlix[Index]",
                                "GDFLix[Index] - $fileName",
                                source,
                                "",
                                getIndexQuality(fileName),
                            )
                        )
                    }
                }
            }
            else if (text.contains("DRIVEBOT LINK"))
            {
                val driveLink = it.attr("href")
                val id = driveLink.substringAfter("id=").substringBefore("&")
                val doId = driveLink.substringAfter("do=").substringBefore("==")
                val baseUrls = listOf("https://drivebot.sbs", "https://drivebot.cfd")
                baseUrls.amap { baseUrl ->
                    val indexbotlink = "$baseUrl/download?id=$id&do=$doId"
                    val indexbotresponse = app.get(indexbotlink, timeout = 100L)
                    if(indexbotresponse.isSuccessful) {
                        val cookiesSSID = indexbotresponse.cookies["PHPSESSID"]
                        val indexbotDoc = indexbotresponse.document
                        val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""").find(indexbotDoc.toString()) ?. groupValues ?. get(1) ?: ""
                        val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""").find(indexbotDoc.toString()) ?. groupValues ?. get(1) ?: ""

                        val requestBody = FormBody.Builder()
                            .add("token", token)
                            .build()

                        val headers = mapOf(
                            "Referer" to indexbotlink
                        )

                        val cookies = mapOf(
                            "PHPSESSID" to "$cookiesSSID",
                        )

                        val response = app.post(
                            "$baseUrl/download?id=${postId}",
                            requestBody = requestBody,
                            headers = headers,
                            cookies = cookies,
                            timeout = 100L
                        ).toString()

                        var downloadlink = Regex("url\":\"(.*?)\"").find(response) ?. groupValues ?. get(1) ?: ""

                        downloadlink = downloadlink.replace("\\", "")

                        callback.invoke(
                            ExtractorLink(
                                "GDFlix[DriveBot]",
                                "GDFlix[DriveBot] - $fileName",
                                downloadlink,
                                baseUrl,
                                getIndexQuality(fileName)
                            )
                        )
                    }
                }
            }
            else if (text.contains("Instant DL"))
            {
                val instantLink = it.attr("href")
                val link = app.get(instantLink, timeout = 30L, allowRedirects = false).headers["Location"]?.split("url=") ?. getOrNull(1) ?: ""
                callback.invoke(
                    ExtractorLink(
                        "GDFlix[Instant Download]",
                        "GDFlix[Instant Download] - $fileName",
                        link,
                        "",
                        getIndexQuality(fileName)
                    )
                )
            }
            else if(text.contains("CLOUD DOWNLOAD [FSL]")) {
                val link = it.attr("href").substringAfter("url=")
                callback.invoke(
                    ExtractorLink(
                        "GDFlix[FSL Instant Download]",
                        "GDFlix[FSL Instant Download] - $fileName",
                        link,
                        "",
                        getIndexQuality(fileName)
                    )
                )
            }
            else {
                Log.d("Error", "No Server matched")
            }
        }
    }
}