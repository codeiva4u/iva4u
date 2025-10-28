package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// MultiMoviesShg Extractor - Main video hoster
class MultiMoviesShgExtractor : ExtractorApi() {
    override val name = "MultiMoviesShg"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("MultiMoviesShg", "Starting extraction for URL: $url")
            
            // Method 1: Try WebView extraction for m3u8
            try {
                Log.d("MultiMoviesShg", "Method 1: Trying WebView extraction...")
                val response = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    interceptor = WebViewResolver(
                        Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                    )
                )
                
                if (response.url.contains("m3u8")) {
                    Log.d("MultiMoviesShg", "WebView extraction successful: ${response.url}")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [WebView]",
                            response.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer ?: url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("MultiMoviesShg", "WebView extraction failed: ${e.message}")
            }
            
            // Method 2: Parse HTML for m3u8 URLs
            try {
                Log.d("MultiMoviesShg", "Method 2: Trying HTML/JS extraction...")
                val doc = app.get(url, referer = referer ?: mainUrl).document
                
                // Look for script tags with m3u8 URLs
                val scripts = doc.select("script")
                val m3u8Regex = Regex("""(https?://[^\"'\\s]+\.m3u8[^\"'\\s]*)""")
                
                scripts.forEach { script ->
                    val scriptContent = script.html()
                    val match = m3u8Regex.find(scriptContent)
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        Log.d("MultiMoviesShg", "HTML extraction successful: $m3u8Url")
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name [HTML]",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: url
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("MultiMoviesShg", "HTML extraction failed: ${e.message}")
            }
            
            Log.e("MultiMoviesShg", "All extraction methods failed")
        } catch (e: Exception) {
            Log.e("MultiMoviesShg", "Extraction error: ${e.message}")
        }
    }
}

// GdMirror/GtxGamer Extractor - Redirects to MultiMoviesShg
class GdMirrorExtractor : ExtractorApi() {
    override val name = "GdMirror"
    override val mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GdMirror", "Fetching: $url")
            
            // Follow redirects to get final URL
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            
            Log.d("GdMirror", "Final URL after redirects: $finalUrl")
            
            // Check if it redirects to gtxgamer or similar
            if (finalUrl.contains("gtxgamer") || finalUrl.contains("embed")) {
                val doc = response.document
                
                // Look for iframe with video source
                val iframes = doc.select("iframe[src]")
                iframes.forEach { iframe ->
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.contains("multimoviesshg") || iframeSrc.contains("/e/")) {
                        Log.d("GdMirror", "Found iframe source: $iframeSrc")
                        
                        // Extract using MultiMoviesShg extractor
                        MultiMoviesShgExtractor().getUrl(
                            iframeSrc,
                            finalUrl,
                            subtitleCallback,
                            callback
                        )
                        return
                    }
                }
            }
            
            Log.e("GdMirror", "No video source found")
        } catch (e: Exception) {
            Log.e("GdMirror", "Extraction error: ${e.message}")
        }
    }
}

// TechInMind/SSN Extractor - For TV shows
class TechInMindExtractor : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Fetching: $url")
            
            val response = app.get(url, allowRedirects = true)
            val doc = response.document
            
            // Look for iframe with ssn.techinmind.space or multimoviesshg
            val iframes = doc.select("iframe[src]")
            iframes.forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                Log.d("TechInMind", "Found iframe: $iframeSrc")
                
                if (iframeSrc.contains("ssn.techinmind.space") || 
                    iframeSrc.contains("multimoviesshg")) {
                    
                    // Follow the iframe
                    val iframeResponse = app.get(iframeSrc, allowRedirects = true)
                    val iframeDoc = iframeResponse.document
                    
                    // Look for nested iframe with multimoviesshg
                    val nestedIframes = iframeDoc.select("iframe[src]")
                    nestedIframes.forEach { nested ->
                        val nestedSrc = nested.attr("src")
                        if (nestedSrc.contains("multimoviesshg") || nestedSrc.contains("/e/")) {
                            Log.d("TechInMind", "Found nested iframe: $nestedSrc")
                            
                            // Extract using MultiMoviesShg extractor
                            MultiMoviesShgExtractor().getUrl(
                                nestedSrc,
                                iframeSrc,
                                subtitleCallback,
                                callback
                            )
                            return
                        }
                    }
                }
            }
            
            Log.e("TechInMind", "No video source found")
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction error: ${e.message}")
        }
    }
}

// Streamwish Extractor
class StreamwishExtractor : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""master\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Streamwish", "Extraction error: ${e.message}")
        }
    }
}

// VidHide Extractor
class VidHideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("VidHide", "Extraction error: ${e.message}")
        }
    }
}

// Filepress Extractor
class FilepressExtractor : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.store"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = referer).document
            
            // Look for video source in HTML
            val videoSrc = doc.select("source[src]").attr("src").ifBlank {
                doc.select("video source").firstOrNull()?.attr("src")
            }
            
            if (!videoSrc.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoSrc,
                        if (videoSrc.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Filepress", "Extraction error: ${e.message}")
        }
    }
}

// Gofile Extractor
class GofileExtractor : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Gofile uses API to get download links
            val contentId = url.substringAfterLast("/")
            val apiUrl = "https://api.gofile.io/contents/$contentId"
            
            val response = app.get(apiUrl).parsedSafe<GofileResponse>()
            response?.data?.contents?.values?.forEach { content ->
                if (content.type == "video" || content.type == "file") {
                    content.link?.let { link ->
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "${name} - ${content.name}",
                                link,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Gofile", "Extraction error: ${e.message}")
        }
    }
    
    data class GofileResponse(
        val data: GofileData?
    )
    
    data class GofileData(
        val contents: Map<String, GofileContent>?
    )
    
    data class GofileContent(
        val type: String?,
        val name: String?,
        val link: String?
    )
}