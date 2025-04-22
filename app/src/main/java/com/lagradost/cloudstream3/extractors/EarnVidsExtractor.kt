package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class EarnVids : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://dhtpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        
        // First check if there's a direct iframe in the page
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null && iframeSrc.isNotEmpty()) {
            // Request the iframe content
            val iframeDoc = app.get(iframeSrc, referer = url).document
            
            // Try to find the video source in the iframe
            val videoSrc = iframeDoc.selectFirst("source")?.attr("src")
            if (videoSrc != null && videoSrc.isNotEmpty()) {
                if (videoSrc.endsWith(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        videoSrc,
                        iframeSrc,
                        headers = mapOf("Referer" to iframeSrc)
                    ).forEach(callback)
                    return
                } else {
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            videoSrc,
                            iframeSrc,
                            Qualities.Unknown.value,
                            videoSrc.endsWith(".m3u8")
                        )
                    )
                    return
                }
            }
        }
        
        // If no direct iframe, try to parse JavaScript to find the source
        val scripts = doc.select("script")
        val scriptContents = scripts.joinToString("\n") { it.html() }
        
        // Try to find a JW Player setup
        val jwPlayerRegex = """jwplayer\("([^"]+)"\).setup\((.*?)\);""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val jwPlayerMatch = jwPlayerRegex.find(scriptContents)
        
        if (jwPlayerMatch != null) {
            val setupJson = jwPlayerMatch.groupValues[2]
            
            // Try to find file URL in the setup
            val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
            val fileMatch = fileRegex.find(setupJson)
            
            if (fileMatch != null) {
                val videoUrl = fileMatch.groupValues[1]
                
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
            
            // Try to find sources array in the setup
            val sourcesRegex = """sources\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val sourcesMatch = sourcesRegex.find(setupJson)
            
            if (sourcesMatch != null) {
                val sourcesContent = sourcesMatch.groupValues[1]
                
                // Extract file URLs and labels from sources
                val fileMatches = fileRegex.findAll(sourcesContent)
                val labelRegex = """label\s*:\s*["']([^"']+)["']""".toRegex()
                val labelMatches = labelRegex.findAll(sourcesContent)
                
                val files = fileMatches.map { it.groupValues[1] }.toList()
                val labels = labelMatches.map { it.groupValues[1] }.toList()
                
                files.forEachIndexed { index, file ->
                    val label = if (index < labels.size) labels[index] else "Unknown"
                    
                    val quality = when (label) {
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    if (file.endsWith(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name,
                            file,
                            url,
                            headers = mapOf("Referer" to url)
                        ).forEach(callback)
                    } else {
                        callback(
                            ExtractorLink(
                                name,
                                "$name - $label",
                                file,
                                url,
                                quality,
                                false
                            )
                        )
                    }
                }
                
                if (files.isNotEmpty()) return
            }
        }
        
        // Try direct regex search for m3u8 URLs
        val m3u8Regex = """["']([^"']+\.m3u8[^"']*)["']""".toRegex()
        val m3u8Matches = m3u8Regex.findAll(scriptContents)
        
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
        
        // Check for a video tag directly in the page
        val videoSrc = doc.selectFirst("video source")?.attr("src")
        if (videoSrc != null && videoSrc.isNotEmpty()) {
            if (videoSrc.endsWith(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    videoSrc,
                    url,
                    headers = mapOf("Referer" to url)
                ).forEach(callback)
            } else {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        videoSrc,
                        url,
                        Qualities.Unknown.value,
                        false
                    )
                )
            }
            return
        }
        
        // If we've made it here, we need to check if we need to unpack JavaScript
        val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?}\(.*?\\'\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val packedMatches = packedRegex.findAll(scriptContents)
        
        for (packedMatch in packedMatches) {
            try {
                val unpackedCode = app.unpackJs(packedMatch.value)
                
                // Search for source URLs in the unpacked code
                val sourceRegex = """source\s*:\s*["']([^"']+)["']""".toRegex()
                val sourceMatch = sourceRegex.find(unpackedCode)
                
                if (sourceMatch != null) {
                    val videoUrl = sourceMatch.groupValues[1]
                    
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