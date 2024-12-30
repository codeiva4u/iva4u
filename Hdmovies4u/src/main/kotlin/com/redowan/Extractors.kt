package com.redowan

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.M3u8Helper

class WishOnly : ExtractorApi() {
    override val name = "WishOnly"
    override val mainUrl = "https://wishonly.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer).text
        val sources = mutableListOf<ExtractorLink>()

        // Extract m3u8 links
        val masterPlaylistUrl = Regex("master\\.m3u8(.*?)\"").find(response)?.groupValues?.get(1)
        if (masterPlaylistUrl != null) {
            val m3u8Url = if (masterPlaylistUrl.startsWith("http")) {
                masterPlaylistUrl
            } else {
                "$mainUrl/video007/ind$masterPlaylistUrl" // संभावित बग फिक्स: सही URL निर्माण
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

        // Extract video link if no m3u8 found, (this might not be reliable)
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