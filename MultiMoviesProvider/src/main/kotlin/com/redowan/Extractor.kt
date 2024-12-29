package com.redowan

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

open class Multimovies : ExtractorApi() {
    override val name = "MultimoviesExtractor"
    override val mainUrl = "https://multimovies.lat"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val doc = Jsoup.connect(url).get()

        // Check if the link is from multimovies.cloud, animezia.cloud, oneupload.to, or other supported sites
        when {
            url.contains("multimovies.cloud") -> {
                val iframe = doc.selectFirst("iframe")?.attr("src")
                if (!iframe.isNullOrBlank()) {
                    links.add(ExtractorLink(
                        name,
                        name,
                        iframe,
                        url,
                        getQualityFromName(iframe),
                        isM3u8 = iframe.contains(".m3u8")
                    ))
                }
            }
            url.contains("animezia.cloud") || url.contains("oneupload.to") -> {
                // You'll need to add specific logic to handle these sites
                // This is a placeholder. You might need to follow redirects or extract
                // data from embedded players.
                // For example, if the URL redirects to the actual video, you can try:
                val connection = Jsoup.connect(url).followRedirects(true)
                val redirectedUrl = connection.execute().url().toString()
                if (redirectedUrl.contains(".mp4") || redirectedUrl.contains(".m3u8")){
                    links.add(
                        ExtractorLink(
                            name,
                            name,
                            redirectedUrl,
                            url,
                            getQualityFromName(redirectedUrl),
                            isM3u8 = redirectedUrl.contains(".m3u8")
                        )
                    )
                } else {
                    // If no direct link is found after redirect you might need to parse the HTML
                    val videoUrl = doc.selectFirst("video source")?.attr("src") // Example selector
                    if (!videoUrl.isNullOrBlank()) {
                        links.add(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                url,
                                getQualityFromName(videoUrl),
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                    }
                }
            }
            // Add more cases as needed for other supported sites
        }

        return links
    }
}