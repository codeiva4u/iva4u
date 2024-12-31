package com.redowan

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.M3u8Helper

open class FilePressLife : ExtractorApi() {
    override var name = "FilePressLife"
    override var mainUrl = "https://new2.filepress.life"
    override var requiresReferer = false

    // override कीवर्ड हटा दिया गया
    fun canHandleUrl(url: String): Boolean {
        return url.contains("new2.filepress.life")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer).text
        val sources = mutableListOf<ExtractorLink>()

        val iframeUrl = Regex("<a [^>]*href=\"(.*?)\"[^>]*>[\\s]*<button [^>]*>Watch Now<\\/button>")
            .find(response)?.groupValues?.get(1)

        if (iframeUrl != null) {
            val iframeResponse = app.get(iframeUrl, referer = url).text

            val videoUrl = Regex("file:\"(.*?)\",label:\"(.*?)\"").find(iframeResponse)?.groupValues?.get(1)?.replace("\\/", "/")
            val qualityName = Regex("file:\"(.*?)\",label:\"(.*?)\"").find(iframeResponse)?.groupValues?.get(2)

            if (videoUrl != null) {
                sources.add(
                    ExtractorLink(
                        source = name,
                        name = "$name ${qualityName ?: ""}",
                        url = videoUrl,
                        referer = iframeUrl,
                        quality = getQualityFromName(qualityName),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
        return sources
    }
}

open class WishOnly : ExtractorApi() {
    override var name = "WishOnly"
    override var mainUrl = "https://wishonly.site"
    override var requiresReferer = false

    // override कीवर्ड हटा दिया गया
    fun canHandleUrl(url: String): Boolean {
        return url.contains("wishonly.site")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer).text
        val sources = mutableListOf<ExtractorLink>()

        val masterPlaylistUrl = Regex("master\\.m3u8(.*?)\"").find(response)?.groupValues?.get(1)
        if (masterPlaylistUrl != null) {
            val m3u8Url = if (masterPlaylistUrl.startsWith("http")) {
                masterPlaylistUrl
            } else {
                "$mainUrl/video007/ind$masterPlaylistUrl"
            }

            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = url,
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).forEach {
                sources.add(it)
            }
        }

        if (sources.isEmpty()) {
            val videoUrl = Regex("\"file\":\"(.*?)\"").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            if (videoUrl != null) {
                sources.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = url,
                        quality = getQualityFromName(videoUrl),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }

        return sources
    }
}

open class SdSpXyz : ExtractorApi() {
    override var name = "SdSpXyz"
    override var mainUrl = "https://v1.sdsp.xyz"
    override var requiresReferer = false

    // override कीवर्ड हटा दिया गया
    fun canHandleUrl(url: String): Boolean {
        return url.contains("v1.sdsp.xyz")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer).text
        val sources = mutableListOf<ExtractorLink>()

        val videoUrlRegex = Regex("file:\"(.*?)\",label:\"(.*?)\"")
        videoUrlRegex.findAll(response).forEach { matchResult ->
            val videoUrl = matchResult.groupValues[1].replace("\\/", "/")
            val qualityName = matchResult.groupValues[2]
            sources.add(
                ExtractorLink(
                    source = name,
                    name = "$name ${qualityName}",
                    url = videoUrl,
                    referer = url,
                    quality = getQualityFromName(qualityName),
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        if (sources.isEmpty()){
            val m3u8Url = Regex("""["'](.*?\.m3u8.*?)["']""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")

            if (m3u8Url != null) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = url
                ).forEach {
                    sources.add(it)
                }
            }
        }

        return sources
    }
}