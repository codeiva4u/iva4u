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
        val videoId = url.substringAfter("#").substringBefore("&")
        if (videoId.isEmpty() || videoId == url) return
        
        // Use download parameter which might provide direct video file
        val downloadUrl = "$url&dl=1"
        
        callback.invoke(
            newExtractorLink(
                name,
                "$name [Download]",
                downloadUrl
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
