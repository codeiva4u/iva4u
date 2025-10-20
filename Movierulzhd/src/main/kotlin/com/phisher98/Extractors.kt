package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract video ID from URL fragment
        // URL format: https://cherry.upns.online/#{videoId}
        val videoId = url.substringAfter("#")
        if (videoId.isEmpty() || videoId == url) return
        
        // API endpoint returns actual video file
        // The API endpoint itself IS the video source
        val videoUrl = "$mainUrl/api/v1/video?id=$videoId"
        
        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl
            ) {
                this.referer = url  // Use original iframe URL as referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
