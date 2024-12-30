package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup


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
        // Fetch the HTML content
        val html = app.get(url).text

        // Parse the HTML using Jsoup
        val document = Jsoup.parse(html)

        // Extract file name
        val fileName = document.select("title").text()
            .replace(" - ViiR PrivateMovieZ.mkv ~ pixeldrain", "")
            .trim()

        // Extract file size
        val fileSize = document.select("div.stat").first { it.text().contains("GB") }.text()

        // Extract direct download link
        val directDownloadLink = document.select("a[href*='/api/file/']")
            .firstOrNull { it.text().contains("Download") }
            ?.attr("href")

        // Extract embedded link
        val embeddedLink = document.select("iframe[src*='pixeldra.in']")
            .firstOrNull()
            ?.attr("src")

        // Extract thumbnail URL
        val thumbnailUrl = document.select("meta[property='og:image']")
            .firstOrNull()
            ?.attr("content")

        // Extract SHA256 hash
        val sha256Hash = document.select("script:containsData(hash_sha256)")
            .firstOrNull()
            ?.html()
            ?.let { Regex("hash_sha256\":\"([a-f0-9]+)\"").find(it)?.groupValues?.get(1) }

        // If direct download link is found, invoke the callback
        if (!directDownloadLink.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    "$name [Direct]",
                    "$name [Direct] - $fileName",
                    directDownloadLink,
                    url,
                    Qualities.Unknown.value,
                )
            )
        }

        // If embedded link is found, extract the file ID and construct the download link
        if (!embeddedLink.isNullOrEmpty()) {
            val fileId = Regex("/u/(.*)\\?embed").find(embeddedLink)?.groupValues?.get(1)
            if (!fileId.isNullOrEmpty()) {
                val downloadUrl = "$mainUrl/api/file/$fileId?download"
                callback.invoke(
                    ExtractorLink(
                        "$name [Embedded]",
                        "$name [Embedded] - $fileName",
                        downloadUrl,
                        embeddedLink,
                        Qualities.Unknown.value,
                    )
                )
            }
        }
    }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.tel"
    override val requiresReferer = false

    class HubCloudInk : HubCloud() {
        override val mainUrl: String = "https://hubcloud.ink"
    }

    class HubCloudArt : HubCloud() {
        override val mainUrl: String = "https://hubcloud.art"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = url.replace("ink", "tel").replace("art", "tel")
        val doc = app.get(newUrl).document
        val link = if(url.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
        }
        else {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }

        val document = app.get(link).document
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
            else if (text.contains("Download File")) {
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
                        dlink.substringAfter("url="),
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

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }
}