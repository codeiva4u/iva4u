package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class Multimovies : ExtractorApi() {
    override val name = "Multimovies"
    override val mainUrl = "https://multimovies.cloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // First, get the iframe content from the provided URL
        val doc = app.get(url, referer = referer).document
        
        // Check if the page contains a vidFrame iframe which is the primary player
        val vidFrameSrc = doc.selectFirst("iframe#vidFrame")?.attr("src")
        if (vidFrameSrc != null) {
            // Load the iframe source
            val iframeDoc = app.get(vidFrameSrc, referer = url).document
            
            // Try to find script with JW Player setup
            val scriptWithSetup = iframeDoc.select("script").find { it.data().contains("jwplayer") }
            
            if (scriptWithSetup != null) {
                // Extract the player setup
                val playerSetup = scriptWithSetup.data()
                
                // Try to find m3u8 URLs
                val m3u8Regex = """["']([^"']+\.m3u8[^"']*)["']""".toRegex()
                val m3u8Matches = m3u8Regex.findAll(playerSetup)
                
                val m3u8Urls = m3u8Matches.map { it.groupValues[1] }
                    .filter { it.startsWith("http") }
                    .toList()
                
                if (m3u8Urls.isNotEmpty()) {
                    for (m3u8Url in m3u8Urls) {
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Url,
                            url,
                            headers = mapOf("Referer" to vidFrameSrc)
                        ).forEach(callback)
                    }
                    return
                }
                
                // Try to find sources in JW Player config
                val sourcesRegex = """sources\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesRegex.find(playerSetup)
                
                if (sourcesMatch != null) {
                    val sourcesContent = sourcesMatch.groupValues[1]
                    
                    // Extract file URLs
                    val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
                    val fileMatches = fileRegex.findAll(sourcesContent)
                    
                    val files = fileMatches.map { it.groupValues[1] }.toList()
                    
                    for (file in files) {
                        if (file.endsWith(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                name,
                                file,
                                url,
                                headers = mapOf("Referer" to vidFrameSrc)
                            ).forEach(callback)
                        } else {
                            callback(
                                ExtractorLink(
                                    name,
                                    name,
                                    file,
                                    vidFrameSrc,
                                    Qualities.Unknown.value,
                                    false
                                )
                            )
                        }
                    }
                    return
                }
            }
            
            // If we reach here, we couldn't find sources in the iframe directly
            // Try to load it using a general extractor
            loadExtractor(vidFrameSrc, url, subtitleCallback, callback)
            return
        }
        
        // If we can't find the primary iframe, look for alternative video links
        val videoLinks = doc.select("ul#videoLinks li")
        if (videoLinks.isNotEmpty()) {
            for (link in videoLinks) {
                val dataLink = link.attr("data-link")
                val sourceKey = link.attr("data-source-key")
                
                if (dataLink.isNotEmpty() && !dataLink.contains("Download")) {
                    if (dataLink.startsWith("http")) {
                        loadExtractor(dataLink, url, subtitleCallback, callback)
                    }
                }
            }
            return
        }
        
        // Last resort: check if there's a vidFreme iframe (alternative spelling in the HTML)
        val vidFremeSrc = doc.selectFirst("iframe#vidFreme")?.attr("src")
        if (vidFremeSrc != null) {
            loadExtractor(vidFremeSrc, url, subtitleCallback, callback)
            return
        }
        
        throw ErrorLoadingException("No playable sources found")
    }
} 