package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName

// Drivetot
class Drivetot : ExtractorApi() {
    override var name = "Drivetot"
    override var mainUrl = "https://drivetot.dad" // मुख्य URL बदल सकता है, वेबसाइट चेक करें
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)

        val videoUrl = response.document.select("video#myVideo source").attr("src")

        if (videoUrl.isNullOrBlank()) return

        callback.invoke(
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

// Doodstream
open class DoodStream : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com" // मुख्य URL बदल सकता है
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val packedText = getPacked(response.text)

        if (packedText != null) {
            val unpacked = getAndUnpack(packedText)

            val videoUrl = Regex("file:\\s*\"(.*?)\"").find(unpacked)?.groupValues?.get(1)
            val quality = Regex("""label:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        videoUrl,
                        referer ?: url,
                        quality = getQualityFromName(quality),
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}

// Streamwish
open class StreamWish : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://streamwish.to" // मुख्य URL बदल सकता है
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to "$mainUrl/",
            "User-Agent" to USER_AGENT,
            "Referer" to url
        )
        val response = app.get(url, referer = referer, headers = headers)

        val script = response.document.select("script:containsData(sources:)").firstOrNull()?.data()
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)

        M3u8Helper.generateM3u8(
            name,
            m3u8 ?: return,
            referer ?: url,
            headers = headers
        ).forEach(callback)
    }
}

// Voe
open class Voe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        val script = document.select("script:containsData(sources:)").firstOrNull()?.data() ?: return
        val m3u8Url = Regex("""file:\s*"(.*?)"""").find(script)?.groupValues?.get(1) ?: return

        M3u8Helper.generateM3u8(
            name,
            m3u8Url,
            referer ?: url
        ).forEach(callback)
    }
}