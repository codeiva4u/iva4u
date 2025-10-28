package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
            
            val doc = app.get(url, referer = referer ?: mainUrl).document
            
            // Method 1: Parse HTML/JavaScript for m3u8 URLs
            try {
                Log.d("MultiMoviesShg", "Method 1: Trying HTML/JS extraction...")
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
                                this.referer = url
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }
                }
                Log.d("MultiMoviesShg", "No M3U8 found in scripts, trying body text...")
                
                // Try to find m3u8 in page body
                val bodyText = doc.body().html()
                val bodyMatch = m3u8Regex.find(bodyText)
                if (bodyMatch != null) {
                    val m3u8Url = bodyMatch.groupValues[1]
                    Log.d("MultiMoviesShg", "Body extraction successful: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [Body]",
                            m3u8Url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("MultiMoviesShg", "HTML extraction failed: ${e.message}")
            }
            
            // Method 2: Try WebView extraction as fallback
            try {
                Log.d("MultiMoviesShg", "Method 2: Trying WebView extraction...")
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
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("MultiMoviesShg", "WebView extraction failed: ${e.message}")
            }
            
            Log.e("MultiMoviesShg", "All extraction methods failed")
        } catch (e: Exception) {
            Log.e("MultiMoviesShg", "Extraction error: ${e.message}")
            e.printStackTrace()
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
            Log.d("GdMirror", "Starting extraction for: $url")
            
            // Method 1: Use WebView to load dynamic content and capture redirects
            try {
                Log.d("GdMirror", "Method 1: Using WebView to capture dynamic redirects...")
                val webViewResponse = app.get(
                    url,
                    referer = referer,
                    interceptor = WebViewResolver(
                        Regex("""multimoviesshg\.com/e/[a-zA-Z0-9]+""")
                    )
                )
                
                val capturedUrl = webViewResponse.url
                Log.d("GdMirror", "WebView captured URL: $capturedUrl")
                
                if (capturedUrl.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("GdMirror", "Found MultiMoviesShg URL via WebView: $capturedUrl")
                    MultiMoviesShgExtractor().getUrl(
                        capturedUrl,
                        url,
                        subtitleCallback,
                        callback
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("GdMirror", "WebView method failed: ${e.message}")
            }
            
            // Method 2: Follow redirects manually
            try {
                Log.d("GdMirror", "Method 2: Following redirects manually...")
                val response = app.get(url, allowRedirects = true)
                val finalUrl = response.url
                val doc = response.document
                
                Log.d("GdMirror", "Landed on: $finalUrl")
                
                // If landed on gtxgamer, wait and check for iframes
                if (finalUrl.contains("gtxgamer", ignoreCase = true)) {
                    // Check for iframes
                    val iframes = doc.select("iframe[src]")
                    iframes.forEach { iframe ->
                        val iframeSrc = iframe.attr("abs:src")
                        Log.d("GdMirror", "Found iframe: $iframeSrc")
                        
                        if (iframeSrc.contains("multimoviesshg", ignoreCase = true)) {
                            Log.d("GdMirror", "Found MultiMoviesShg iframe: $iframeSrc")
                            MultiMoviesShgExtractor().getUrl(
                                iframeSrc,
                                finalUrl,
                                subtitleCallback,
                                callback
                            )
                            return
                        }
                    }
                    
                    // Check body for multimoviesshg URLs
                    val bodyText = doc.body().html()
                    val multiMoviesRegex = Regex("""(https?://multimoviesshg\.com/e/[a-zA-Z0-9]+)""")
                    val multiMoviesMatch = multiMoviesRegex.find(bodyText)
                    if (multiMoviesMatch != null) {
                        val multiMoviesUrl = multiMoviesMatch.groupValues[1]
                        Log.d("GdMirror", "Found MultiMoviesShg URL in HTML: $multiMoviesUrl")
                        MultiMoviesShgExtractor().getUrl(
                            multiMoviesUrl,
                            finalUrl,
                            subtitleCallback,
                            callback
                        )
                        return
                    }
                }
                
                // If directly landed on multimoviesshg
                if (finalUrl.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("GdMirror", "Directly landed on MultiMoviesShg: $finalUrl")
                    MultiMoviesShgExtractor().getUrl(
                        finalUrl,
                        url,
                        subtitleCallback,
                        callback
                    )
                    return
                }
            } catch (e: Exception) {
                Log.e("GdMirror", "Manual redirect method failed: ${e.message}")
            }
            
            Log.e("GdMirror", "All extraction methods failed for: $url")
        } catch (e: Exception) {
            Log.e("GdMirror", "Fatal extraction error: ${e.message}")
            e.printStackTrace()
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
                val iframeSrc = iframe.attr("abs:src")
                Log.d("TechInMind", "Found iframe: $iframeSrc")
                
                // If iframe directly contains multimoviesshg, extract it
                if (iframeSrc.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("TechInMind", "Found MultiMoviesShg iframe directly: $iframeSrc")
                    MultiMoviesShgExtractor().getUrl(
                        iframeSrc,
                        url,
                        subtitleCallback,
                        callback
                    )
                    return
                }
                
                // If iframe contains ssn.techinmind, follow it for nested iframe
                if (iframeSrc.contains("ssn.techinmind.space", ignoreCase = true)) {
                    try {
                        val iframeResponse = app.get(iframeSrc, allowRedirects = true)
                        val iframeDoc = iframeResponse.document
                        
                        // Look for nested iframe with multimoviesshg
                        val nestedIframes = iframeDoc.select("iframe[src]")
                        nestedIframes.forEach { nested ->
                            val nestedSrc = nested.attr("abs:src")
                            if (nestedSrc.contains("multimoviesshg", ignoreCase = true)) {
                                Log.d("TechInMind", "Found nested MultiMoviesShg iframe: $nestedSrc")
                                MultiMoviesShgExtractor().getUrl(
                                    nestedSrc,
                                    iframeSrc,
                                    subtitleCallback,
                                    callback
                                )
                                return
                            }
                        }
                        
                        // Also try to find multimoviesshg URL in iframe source
                        val iframeBodyText = iframeDoc.body().html()
                        val multiMoviesRegex = Regex("""(https?://[^\"'\\s]*multimoviesshg[^\"'\\s]*/e/[^\"'\\s]+)""")
                        val multiMoviesMatch = multiMoviesRegex.find(iframeBodyText)
                        if (multiMoviesMatch != null) {
                            val multiMoviesUrl = multiMoviesMatch.groupValues[1]
                            Log.d("TechInMind", "Found MultiMoviesShg URL in iframe source: $multiMoviesUrl")
                            MultiMoviesShgExtractor().getUrl(
                                multiMoviesUrl,
                                iframeSrc,
                                subtitleCallback,
                                callback
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.e("TechInMind", "Error following nested iframe: ${e.message}")
                    }
                }
            }
            
            Log.e("TechInMind", "No video source found")
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction error: ${e.message}")
            e.printStackTrace()
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

// Buzzheavier Extractor
class BuzzheavierExtractor : ExtractorApi() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Buzzheavier", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("Buzzheavier", "Found M3U8: ${response.url}")
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
            Log.e("Buzzheavier", "Extraction error: ${e.message}")
        }
    }
}

// GDtot Extractor
class GDtotExtractor : ExtractorApi() {
    override val name = "GDtot"
    override val mainUrl = "https://gdtot.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDtot", "Fetching: $url")
            
            val doc = app.get(url, referer = referer).document
            
            // Look for download button or direct link
            val downloadLink = doc.select("a[href*='drive.google.com'], a.btn[href]").attr("href")
            
            if (downloadLink.isNotBlank()) {
                Log.d("GDtot", "Found download link: $downloadLink")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        downloadLink,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("GDtot", "Extraction error: ${e.message}")
        }
    }
}

// RpmShare Extractor
class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://rpmshare.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("RpmShare", "Found M3U8: ${response.url}")
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
            Log.e("RpmShare", "Extraction error: ${e.message}")
        }
    }
}

// StreamP2P Extractor
class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://streamp2p.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamP2P", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamP2P", "Found M3U8: ${response.url}")
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
            Log.e("StreamP2P", "Extraction error: ${e.message}")
        }
    }
}

// UpnShare Extractor
class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://upnshare.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UpnShare", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("UpnShare", "Found M3U8: ${response.url}")
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
            Log.e("UpnShare", "Extraction error: ${e.message}")
        }
    }
}
