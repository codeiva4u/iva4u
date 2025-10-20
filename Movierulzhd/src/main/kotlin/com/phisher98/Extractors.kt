package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

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
        
        try {
            // Fetch the iframe page HTML
            val document = app.get(url).document
            
            // Try to find video/source elements in the player
            val videoSrc = document.select("video source").attr("src")
            if (videoSrc.isNotBlank()) {
                val fullUrl = if (videoSrc.startsWith("http")) videoSrc else "$mainUrl$videoSrc"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
            
            // Fallback: Try API endpoint with proper stream type
            // Cherry uses custom video delivery, mark as M3U8 for better compatibility
            val apiUrl = "$mainUrl/api/v1/video?id=$videoId"
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    apiUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors"
                    )
                }
            )
        } catch (e: Exception) {
            // If everything fails, return nothing
        }
    }
}
