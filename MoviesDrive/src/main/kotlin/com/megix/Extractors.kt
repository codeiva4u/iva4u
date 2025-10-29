package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mId = Regex("/(?:u|file)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
        val finalUrl = if (mId.isNullOrEmpty()) url else "$mainUrl/api/file/$mId?download"

        callback.invoke(
            newExtractorLink(this.name, this.name, finalUrl) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
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
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return // Invalid URL, skip processing
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
                    callback.invoke(
                        newExtractorLink("Pixeldrain", "Pixeldrain $header[$size]", btnLink) {
                            quality = getIndexQuality(header)
                        }
                    )
                }

                text.contains("Download [Server : 10Gbps]") -> {
                    val dlink = app.get(btnLink, allowRedirects = false).headers["location"]?.substringAfter("link=")
                        ?: return@forEach
                    callback.invoke(
                        newExtractorLink("$name[Download]", "$name[Download] $header[$size]", dlink) {
                            quality = getIndexQuality(header)
                        }
                    )
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