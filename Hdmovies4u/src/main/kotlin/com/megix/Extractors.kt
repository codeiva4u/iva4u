package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class PixelDra : ExtractorApi() {
    override val name = "PixelDra"
    override val mainUrl = "https://pixeldra.in"
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
        val newUrl = url.replace("ink", "dad").replace("art", "dad")
        val doc = app.get(newUrl).document

        // Extract the download links from the HTML
        val downloadLinks = doc.select("a.btn.btn-success.btn-lg.h6").mapNotNull {
            val downloadLink = it.attr("href")
            val serverName = it.text().trim()
            if (downloadLink.isNotBlank()) {
                ExtractorLink(
                    "$name - $serverName",
                    "$name - $serverName",
                    downloadLink,
                    url,
                    Qualities.Unknown.value
                )
            } else {
                null
            }
        }

        // Invoke the callback for each download link
        downloadLinks.forEach { callback.invoke(it) }
    }
}


class DriveTot : ExtractorApi() {
    override val name = "DriveTot"
    override val mainUrl = "https://drivetot.zip"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Just pass the URL to HubCloud for processing
        HubCloud().getUrl(url, referer, subtitleCallback, callback)
    }
}
