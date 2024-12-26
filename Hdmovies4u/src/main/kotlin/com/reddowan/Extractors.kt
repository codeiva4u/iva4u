package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class AllSetLol : ExtractorApi() {
    override var name = "AllSet.lol"
    override var mainUrl = "https://allset.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, allowRedirects = true).document
        doc.select("div.entry-content > a").forEach {
            val link = it.attr("href")
            val quality = it.previousElementSibling()?.text() ?: ""
            callback.invoke(
                ExtractorLink(
                    name,
                    "$name - $quality",
                    link,
                    mainUrl,
                    getVideoQuality(quality)
                )
            )
        }
    }
    private fun getVideoQuality(string: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(string ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}


class VeryFastDownload : ExtractorApi() {
    override var name = "VeryFastDownload"
    override var mainUrl = "https://veryfastdownload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
         if(url.lowercase().contains("veryfastdownload"))
        {
            val downloadLink = url.replace("watch2","download2")
            val html = app.get(downloadLink, timeout = 30).document.html()
             val gLink = "openInNewTab\\(\\\\'(.*)\\\\'\\);\"".toRegex().find(html)?.groups?.get(1)?.value.toString()
            callback.invoke(ExtractorLink(
                "G-Direct",
                "G-Direct",
                url = gLink,
                "",
                quality = Qualities.Unknown.value,
            ))
        }
    }
}

class HCloud : ExtractorApi() {
    override var name = "H-Cloud"
    override var mainUrl = "https://hcloud.gg/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, subtitleCallback, callback)
    }
}
