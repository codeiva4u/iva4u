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

open class DoodStream : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com" //  मुख्य URL बदल सकता है
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

            val videoUrl = Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
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
        // 1. Drivetot पेज प्राप्त करें
        val response = app.get(url, referer = referer)

        // 2. HTML में वीडियो URL खोजें
        //    आपको Regex या Jsoup का उपयोग करके वीडियो URL निकालना होगा।
        //    ध्यान दें कि Drivetot अपनी वेबसाइट बदलता रहता है, इसलिए
        //    आपको HTML का  निरीक्षण करके  URL निकालने का तरीका अपडेट करते रहना होगा।
        //
        //    यहाँ एक उदाहरण दिया गया है जो 24 दिसंबर 2024 तक काम करता है:
        val videoUrlRegex = Regex("source src=\"(.*?)\" type=")
        val videoUrl = videoUrlRegex.find(response.text)?.groupValues?.get(1) ?: return

        // 3. गुणवत्ता निकालें (यदि उपलब्ध हो)
        val quality = getQualityFromName(videoUrl)

        // 4. ExtractorLink ऑब्जेक्ट बनाएं और लौटाएं
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                referer = "$mainUrl/",
                quality = quality,
                isM3u8 = videoUrl.contains(".m3u8") // अगर लिंक m3u8 है तो true
            )
        )
    }
}

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
            "User-Agent" to USER_AGENT
        )
        val response = app.get(url, referer = referer)

        val script = response.document.select("script:containsData(sources:)")
            .firstOrNull()?.data()
        val m3u8 =
            Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)

        M3u8Helper.generateM3u8(
            name,
            m3u8 ?: return,
            referer ?: url,
            headers = headers
        ).forEach(callback)
    }
}

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