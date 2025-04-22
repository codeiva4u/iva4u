package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class StreamHG : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://streamhg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        
        // First approach: Try to extract JW Player config
        val jwPlayerSetupRegex = """jwplayer\("vplayer"\).setup\((.*?)\);""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val scriptContent = doc.select("script").joinToString("\n") { it.html() }
        
        val setupMatch = jwPlayerSetupRegex.find(scriptContent)
        if (setupMatch != null) {
            // Try to extract the HLS URL from the player setup
            val hlsRegex = """"hls[0-9]*"\s*:\s*"([^"]+)"""".toRegex()
            val hlsMatch = hlsRegex.find(setupMatch.groupValues[1])
            
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1]
                M3u8Helper.generateM3u8(
                    name,
                    hlsUrl,
                    url,
                    headers = mapOf("Referer" to url)
                ).forEach(callback)
                return
            }
            
            // Try to find sources array
            val sourcesRegex = """sources\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val sourcesMatch = sourcesRegex.find(setupMatch.groupValues[1])
            
            if (sourcesMatch != null) {
                val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
                val fileMatches = fileRegex.findAll(sourcesMatch.groupValues[1])
                
                val sourceUrls = fileMatches.map { it.groupValues[1] }.toList()
                
                for (source in sourceUrls) {
                    if (source.endsWith(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name,
                            source,
                            url,
                            headers = mapOf("Referer" to url)
                        ).forEach(callback)
                    } else {
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                source,
                                url,
                                Qualities.Unknown.value,
                                false
                            )
                        )
                    }
                }
                return
            }
        }
        
        // Second approach: Look for direct m3u8 URLs
        val m3u8Regex = """["']([^"']+\.m3u8[^"']*)["']""".toRegex()
        val m3u8Matches = m3u8Regex.findAll(scriptContent)
        
        val m3u8Urls = m3u8Matches.map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .toList()
        
        if (m3u8Urls.isNotEmpty()) {
            val m3u8Url = m3u8Urls.first()
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                url,
                headers = mapOf("Referer" to url)
            ).forEach(callback)
            return
        }
        
        // Third approach: Check for links variable in JavaScript
        val linksRegex = """links\s*=\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val linksMatch = linksRegex.find(scriptContent)
        
        if (linksMatch != null) {
            val dataLinkRegex = """data-link\s*=\s*["']([^"']+)["']""".toRegex()
            val dataLinkMatches = dataLinkRegex.findAll(doc.html())
            
            val links = dataLinkMatches.map { it.groupValues[1] }.toList()
            if (links.isNotEmpty()) {
                // Try the first link
                val firstLink = links.first()
                if (firstLink.startsWith("http")) {
                    try {
                        // Recursively try to load this link
                        val linkContent = app.get(firstLink, referer = url).text
                        val subM3u8Match = m3u8Regex.find(linkContent)
                        
                        if (subM3u8Match != null) {
                            val m3u8Url = subM3u8Match.groupValues[1]
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                firstLink,
                                headers = mapOf("Referer" to firstLink)
                            ).forEach(callback)
                            return
                        }
                    } catch (e: Exception) {
                        // If the first link fails, continue to the next approach
                    }
                }
            }
        }
        
        throw ErrorLoadingException("No playable sources found")
    }
} 