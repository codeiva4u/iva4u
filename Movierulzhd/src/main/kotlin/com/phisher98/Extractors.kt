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
    override val requiresReferer = false

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
        
        // API endpoint: https://cherry.upns.online/api/v1/video?id={videoId}&w=1440&h=900&r=
        val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=1440&h=900&r="
        
        try {
            val response = app.get(apiUrl, referer = referer ?: url)
            val videoUrl = response.url // Follow redirects to get actual video URL
            
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            // Fallback: if API call fails, try direct API URL
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    apiUrl
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
