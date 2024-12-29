package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        if (mId.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    url,
                    Qualities.Unknown.value,
                )
            )
        } else {
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
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var href = url
        if (href.contains("api/index.php")) {
            href = app.get(url).document.selectFirst("div.main h4 a")?.attr("href") ?: ""
        }
        val doc = app.get(href).document
        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
        val urlValue = Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        if (urlValue.isNotEmpty()) {
            val document = app.get(urlValue).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text() ?: ""
            div?.select("h2 a.btn")?.apmap {
                val link = it.attr("href")
                val text = it.text()
                if (text.contains("Download [FSL Server]")) {
                    callback.invoke(
                        ExtractorLink(
                            "$name[FSL Server]",
                            "$name[FSL Server] - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else if (text.contains("Download [Server : 1]")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else if (text.contains("BuzzServer")) {
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
                } else if (link.contains("pixeldra")) {
                    callback.invoke(
                        ExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else if (text.contains("Download [Server : 10Gbps]")) {
                    val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Download]",
                            "$name[Download] - $header",
                            dlink.substringAfter("url="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.cloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = url.replace("ink", "tel").replace("art", "tel")
        val doc = app.get(newUrl).document

        val fslLink = doc.selectFirst("a:contains(Download [FSL Server])")?.attr("href")
        if (fslLink != null) {
            callback.invoke(
                ExtractorLink(
                    "$name [FSL Server]",
                    "$name [FSL Server]",
                    fslLink,
                    referer ?: mainUrl,
                    Qualities.Unknown.value,
                )
            )
        } else {
            val link: String
            if (newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                link =
                    (newUrl.substringBefore("/drive") + Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(
                        1
                    ))
            } else {
                link = doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            val document = app.get(link).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text() ?: ""
            div?.select("h2 a.btn")?.apmap {
                val link = it.attr("href")
                val text = it.text()

                if (text.contains("Download File")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else if (text.contains("BuzzServer")) {
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
                } else if (link.contains("pixeldra")) {
                    callback.invoke(
                        ExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain - $header",
                            link,
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else if (text.contains("Download [Server : 10Gbps]")) {
                    val dlink = app.get(link, allowRedirects = false).headers["location"] ?: ""
                    callback.invoke(
                        ExtractorLink(
                            "$name[Download]",
                            "$name[Download] - $header",
                            dlink.substringAfter("url="),
                            "",
                            getIndexQuality(header),
                        )
                    )
                } else {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}