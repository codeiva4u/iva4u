package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Gofile
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

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new10.gdflix.dad"
    override val requiresReferer = false

    private suspend fun getLatestUrl(): String {
        return try {
            val json = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
            JSONObject(json).optString("gdflix", mainUrl)
        } catch (e: Exception) {
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

        document.select("div.text-center a").forEach { anchor ->
            val text = anchor.text()
            val href = anchor.attr("href")

            when {
                text.contains("DIRECT DL") -> {
                    callback.invoke(newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", href) {
                        quality = getIndexQuality(fileName)
                    })
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    callback.invoke(newExtractorLink("GDFlix[Cloud Download]", "GDFlix[Cloud Download] $fileName[$fileSize]", href) {
                        quality = getIndexQuality(fileName)
                    })
                }

                text.contains("PixelDrain DL") -> {
                    callback.invoke(newExtractorLink("Pixeldrain", "Pixeldrain $fileName[$fileSize]", href) {
                        quality = getIndexQuality(fileName)
                    })
                }

                text.contains("Index Links") -> {
                    try {
                        val indexDoc = app.get("$latestUrl$href").document
                        indexDoc.select("a.btn.btn-outline-info").forEach { btn ->
                            val serverUrl = latestUrl + btn.attr("href")
                            val serverDoc = app.get(serverUrl).document
                            serverDoc.select("div.mb-4 > a").forEach { link ->
                                callback.invoke(newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", link.attr("href")) {
                                    quality = getIndexQuality(fileName)
                                })
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

                text.contains("DRIVEBOT") -> {
                    // You can keep your existing DriveBot logic here
                }

                text.contains("Instant DL") -> {
                    try {
                        val redirect = app.get(href, allowRedirects = false).headers["location"]
                        val finalLink = redirect?.substringAfter("url=").orEmpty()
                        callback.invoke(newExtractorLink("GDFlix[Instant Download]", "GDFlix[Instant Download] $fileName[$fileSize]", finalLink) {
                            quality = getIndexQuality(fileName)
                        })
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }

                text.contains("GoFile") -> {
                    try {
                        val doc = app.get(href).document
                        doc.select(".row .row a").forEach { gofileAnchor ->
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
                    Log.d("Error", "No matching server for $text")
                }
            }
        }
    }
}