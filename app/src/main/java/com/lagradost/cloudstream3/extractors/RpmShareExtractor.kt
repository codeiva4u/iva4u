package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class RpmShare : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract the unique hash ID from the URL
        val hashId = url.substringAfter("#").trim()
        if (hashId.isEmpty()) {
            throw ErrorLoadingException("Invalid URL format")
        }
        
        // Construct the API endpoint URL
        val apiUrl = "$mainUrl/api/source/$hashId"
        
        try {
            // Make a POST request to the API endpoint
            val response = app.post(
                apiUrl,
                referer = referer ?: url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("r" to (referer ?: ""), "d" to mainUrl.substringAfter("//"))
            ).text
            
            // Try to find direct m3u8 URLs in the response
            val m3u8Regex = """"file":"([^"]+\.m3u8[^"]*)"""".toRegex()
            val m3u8Matches = m3u8Regex.findAll(response)
            
            val m3u8Urls = m3u8Matches.map { 
                it.groupValues[1].replace("\\", "") 
            }.toList()
            
            if (m3u8Urls.isNotEmpty()) {
                for (m3u8Url in m3u8Urls) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        url,
                        headers = mapOf("Referer" to url)
                    ).forEach(callback)
                }
                return
            }
            
            // Try to extract direct MP4 links with qualities
            val qualityRegex = """"label":"([^"]+)","file":"([^"]+)"""".toRegex()
            val qualityMatches = qualityRegex.findAll(response)
            
            val sourcesList = qualityMatches.map {
                Pair(it.groupValues[1], it.groupValues[2].replace("\\", ""))
            }.toList()
            
            for ((quality, sourceUrl) in sourcesList) {
                val qualityValue = when (quality) {
                    "1080p" -> Qualities.P1080.value
                    "720p" -> Qualities.P720.value
                    "480p" -> Qualities.P480.value
                    "360p" -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                callback(
                    ExtractorLink(
                        name,
                        "$name - $quality",
                        sourceUrl,
                        url,
                        qualityValue,
                        false
                    )
                )
            }
            
            if (sourcesList.isNotEmpty()) return
            
            // If nothing found, try the alternative approach with the video player page
            val doc = app.get(url, referer = referer).document
            val playerScript = doc.select("script").find { 
                it.html().contains("player") && it.html().contains("sources") 
            }?.html() ?: ""
            
            val sourcesRegex = """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""".toRegex()
            val sourceMatch = sourcesRegex.find(playerScript)
            
            if (sourceMatch != null) {
                val sourceUrl = sourceMatch.groupValues[1]
                if (sourceUrl.endsWith(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        sourceUrl,
                        url,
                        headers = mapOf("Referer" to url)
                    ).forEach(callback)
                } else {
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            sourceUrl,
                            url,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                }
                return
            }
            
        } catch (e: Exception) {
            // If the API request fails, try direct page scraping
            val doc = app.get(url, referer = referer).document
            
            // Look for m3u8 URLs in any script tags
            val scripts = doc.select("script")
            val scriptContent = scripts.joinToString("\n") { it.html() }
            
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
        }
        
        throw ErrorLoadingException("No playable sources found")
    }
} 