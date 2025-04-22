package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class UpnShare : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract the hash ID from the URL - it's in format https://server1.uns.bio/#hashid
        val hashId = url.substringAfter("#").trim()
        if (hashId.isEmpty()) {
            throw ErrorLoadingException("Invalid URL format")
        }
        
        val doc = app.get(url, referer = referer).document
        
        // First approach: Try to find player setup in JavaScript
        val scripts = doc.select("script")
        val scriptContent = scripts.joinToString("\n") { it.html() }
        
        // Look for the player setup
        val playerSetupRegex = """var\s+player\s*=\s*new\s+Clappr.Player\s*\((.*?)\);""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val playerSetupMatch = playerSetupRegex.find(scriptContent)
        
        if (playerSetupMatch != null) {
            val playerSetup = playerSetupMatch.groupValues[1]
            
            // Extract the source URL
            val sourceRegex = """source\s*:\s*["']([^"']+)["']""".toRegex()
            val sourceMatch = sourceRegex.find(playerSetup)
            
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
        }
        
        // Second approach: Look for direct source definitions
        val sourceRegex = """sources\s*:\s*\[\s*\{\s*src\s*:\s*["']([^"']+)["']""".toRegex()
        val sourceMatch = sourceRegex.find(scriptContent)
        
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
        
        // Third approach: Try to make a POST request to the API
        try {
            val apiUrl = "$mainUrl/api/source/$hashId"
            
            val response = app.post(
                apiUrl,
                referer = url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("r" to (referer ?: ""), "d" to mainUrl.substringAfter("//"))
            ).text
            
            // Try to find m3u8 links in the response
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
        } catch (e: Exception) {
            // If the API request fails, continue with other approaches
        }
        
        // Fourth approach: Look for m3u8 links directly in the script content
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
        
        // Fifth approach: Look for packed JavaScript and unpack it
        val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?}\(.*?\\'\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val packedMatches = packedRegex.findAll(scriptContent)
        
        for (packedMatch in packedMatches) {
            try {
                val unpackedCode = app.unpackJs(packedMatch.value)
                
                // Search for source URLs in the unpacked code
                val unpackedSourceRegex = """source\s*:\s*["']([^"']+)["']""".toRegex()
                val unpackedSourceMatch = unpackedSourceRegex.find(unpackedCode)
                
                if (unpackedSourceMatch != null) {
                    val videoUrl = unpackedSourceMatch.groupValues[1]
                    
                    if (videoUrl.endsWith(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name,
                            videoUrl,
                            url,
                            headers = mapOf("Referer" to url)
                        ).forEach(callback)
                    } else {
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                url,
                                Qualities.Unknown.value,
                                false
                            )
                        )
                    }
                    return
                }
                
                // Check for m3u8 URLs in the unpacked code
                val unpackedM3u8Matches = m3u8Regex.findAll(unpackedCode)
                val unpackedM3u8Urls = unpackedM3u8Matches.map { it.groupValues[1] }
                    .filter { it.startsWith("http") }
                    .toList()
                
                if (unpackedM3u8Urls.isNotEmpty()) {
                    val m3u8Url = unpackedM3u8Urls.first()
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        url,
                        headers = mapOf("Referer" to url)
                    ).forEach(callback)
                    return
                }
            } catch (e: Exception) {
                // Continue to the next packed script if unpacking fails
                continue
            }
        }
        
        throw ErrorLoadingException("No playable sources found")
    }
} 