package com.megix

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


class VCloud : ExtractorApi() {
    override val name: String = "VCloud"
    override val mainUrl: String = "https://vcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var href=url
        if (href.contains("api/index.php"))
        {
            href=app.get(url).document.selectFirst("div.main h4 a")?.attr("href") ?:""
        }
        val doc = app.get(href).document
        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?:""
        val urlValue = Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
        if (urlValue.isNotEmpty()) {
            val document = app.get(urlValue).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text() ?: ""
            div?.select("h2 a.btn")?.apmap {
                val link = it.attr("href")
                val text = it.text()
                if (text.contains("Download [FSL Server]"))
                {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL Server]",
                            "$name[FSL Server] - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else if (text.contains("Download [Server : 1]")) {
                    callback.invoke(
                        ExtractorLink(
                            "$name",
                            "$name - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else if(text.contains("BuzzServer")) {
                    val dlink = app.get("$link/download", allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[BuzzServer]",
                            "$name[BuzzServer] - $header",
                            link.substringBeforeLast("/") + dlink,
                            "",
                            getIndexQuality(header),
                        )
                    )
                }

                else if (link.contains("pixeldra")) {
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
                else if (text.contains("Download [Server : 10Gbps]")) {
                    val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Download]",
                            "$name[Download] - $header",
                            dlink.substringAfter("link="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                }
                else
                {
                    loadExtractor(link,"",subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

class HubCloudInk : HubCloud() {
    override val mainUrl: String = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl: String = "https://hubcloud.art"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sanitizedUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(sanitizedUrl).document

        // Case 1: Direct HubCloud link from input field
        doc.selectFirst("input#ilink")?.attr("value")?.let { directLink ->
            callback.invoke(
                ExtractorLink(
                    name,
                    "Hub-Cloud Direct",
                    directLink.replace("ink", "dad"),
                    "",
                    Qualities.Unknown.value
                )
            )
        }

        // Case 2: Process all download buttons
        doc.select("a.btn").apmap { button ->
            val href = button.attr("href")
            val text = button.text()
            val quality = extractQuality(text) // Extract quality from button text

            when {
                // FSL Server
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL]",
                            "$name[FSL] - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // R2 Direct Link
                href.contains("r2.dev") -> {
                    callback.invoke(
                        ExtractorLink(
                            "$name[R2]",
                            "$name[R2] - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // 10Gbps Server (Redirect Handling)
                text.contains("10Gbps", ignoreCase = true) -> {
                    val redirectUrl = app.get(href, allowRedirects = false).headers["location"]
                    redirectUrl?.let { safeUrl ->
                        callback.invoke(
                            ExtractorLink(
                                "$name[10Gbps]",
                                "$name[10Gbps] - ${quality}p",
                                safeUrl.substringAfter("link="),
                                "",
                                quality
                            )
                        )
                    }
                }

                // PixelDrain Server
                href.contains("pixeldra.in") -> {
                    callback.invoke(
                        ExtractorLink(
                            "PixelDrain",
                            "PixelDrain - ${quality}p",
                            href,
                            "",
                            quality
                        )
                    )
                }

                // Default case for other links
                else -> loadExtractor(href, "", subtitleCallback, callback)
            }
        }
    }

    // Improved quality extraction logic
    private fun extractQuality(text: String): Int {
        return when {
            Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P720.value
            Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P1080.value
            Regex("4K|2160p", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}

class fastdlserver : GDFlix() {
    override var mainUrl = "https://fastdlserver.online"
}

open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://new.gdflix.dad"
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
                    val trueurl=app.get("https://new.gdflix.dad$link", timeout = 100L).document.selectFirst("a.btn-success")?.attr("href") ?:""
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
                val doc = app.get("https://new.gdflix.dad$link").document
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
                        "GDFlix[FSL]",
                        "GDFlix[FSL] - $fileName",
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
