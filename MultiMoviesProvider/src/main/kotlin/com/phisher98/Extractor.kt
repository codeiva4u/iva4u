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
import org.json.JSONObject
import java.net.URI

// Utility functions for dynamic URL management
fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

suspend fun getLatestUrl(url: String, source: String): String {
    val link = JSONObject(
        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
    ).optString(source)
    if (link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

// MultiMoviesShgExtractor removed - replaced by StreamHGExtractor

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
            
            // Dynamic URL management - fetch latest domain
            val latestUrl = getLatestUrl(url, "gdmirror")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            // Allow redirects to handle potential hops
            val response = app.get(newUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url

            // 1. Check for default iframe (usually StreamHG)
            // Structure: <iframe id="vidFrame" src="...">
            doc.select("iframe[src], iframe#vidFrame").forEach { iframe ->
                val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                Log.d("GdMirror", "Found iframe: $src")
                
                if (src.contains("multimoviesshg", ignoreCase = true)) {
                    StreamHGExtractor().getUrl(src, finalUrl, subtitleCallback, callback)
                } 
                // Add other iframe checks if needed
            }

            // 2. Check for server list (The "3 dots" menu items)
            // Structure: <li class="server-item" data-link="..."><div class="server-meta">Name • ONLINE</div></li>
            val serverItems = doc.select("li.server-item")
            if (serverItems.isNotEmpty()) {
                Log.d("GdMirror", "Found ${serverItems.size} server items")
                serverItems.forEach { item ->
                    val link = item.attr("data-link")
                    val meta = item.select(".server-meta").text()
                    val name = item.select(".server-name").text()
                    
                    if (link.isNotBlank()) {
                         Log.d("GdMirror", "Processing server: $name ($meta) -> $link")
                         
                         if (meta.contains("EarnVids", ignoreCase = true) || name.contains("FLLS", ignoreCase = true)) {
                            EarnVidsExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                         } else if (meta.contains("StreamHG", ignoreCase = true) || name.contains("SMWH", ignoreCase = true)) {
                            StreamHGExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                         } else if(meta.contains("UpnShare", ignoreCase = true) || name.contains("UPNSHR", ignoreCase = true)) {
                             UpnShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                         } else if(meta.contains("StreamP2p", ignoreCase = true) || name.contains("STRMP2", ignoreCase = true)) {
                             StreamP2PExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                         } else if(meta.contains("RpmShare", ignoreCase = true) || name.contains("RPMSHRE", ignoreCase = true)) {
                             RpmShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                         }
                    }
                }
            } else {
                Log.d("GdMirror", "No server list found, trying fallback extraction methods")
                
                // Fallback: Check body text for hidden StreamHG Links
                val bodyText = doc.body().html()
                val multiMoviesRegex = Regex("""(https?://[^"'\s]*multimoviesshg[^"'\s]*/e/[a-zA-Z0-9]+)""")
                val multiMoviesMatch = multiMoviesRegex.find(bodyText)
                if (multiMoviesMatch != null) {
                    val foundUrl = multiMoviesMatch.groupValues[1]
                    StreamHGExtractor().getUrl(foundUrl, finalUrl, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            Log.e("GdMirror", "Fatal extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// TechInMind/SSN Extractor - For TV shows and movies
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
            
            // Dynamic URL management - fetch latest domain
            val latestUrl = getLatestUrl(url, "techinmind")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val response = app.get(newUrl, allowRedirects = true)
            val doc = response.document
            
            // NEW: Handle stream.techinmind.space/embed/* pages with quality selection
            if (url.contains("stream.techinmind.space") && url.contains("/embed/")) {
                Log.d("TechInMind", "Detected stream.techinmind.space embed page")
                
                // Extract quality links from data-link attributes
                val qualityLinks = doc.select("#quality-links a[data-link], a[data-link]")
                if (qualityLinks.isNotEmpty()) {
                    Log.d("TechInMind", "Found ${qualityLinks.size} quality links")
                    qualityLinks.forEach { link ->
                        val dataLink = link.attr("data-link")
                        if (dataLink.isNotBlank()) {
                            Log.d("TechInMind", "Processing quality link: $dataLink")
                            extractFromSSN(dataLink, url, subtitleCallback, callback)
                        }
                    }
                    return
                }
                
                // Fallback: Check for direct iframe in stream page (iframe#player or iframe#vidFrame)
                val playerIframe = doc.selectFirst("iframe#player, iframe#vidFrame, iframe[allowfullscreen]")
                if (playerIframe != null) {
                    val iframeSrc = playerIframe.attr("abs:src").ifBlank { playerIframe.attr("src") }
                    Log.d("TechInMind", "Found player iframe: $iframeSrc")
                    
                    if (iframeSrc.contains("ssn.techinmind", ignoreCase = true)) {
                        extractFromSSN(iframeSrc, url, subtitleCallback, callback)
                        return
                    } else if (iframeSrc.contains("multimoviesshg", ignoreCase = true)) {
                        StreamHGExtractor().getUrl(iframeSrc, url, subtitleCallback, callback)
                        return
                    }
                }
            }
            
            // Handle SSN pages directly
            if (url.contains("ssn.techinmind", ignoreCase = true)) {
                extractFromSSN(url, referer ?: url, subtitleCallback, callback)
                return
            }
            
            // Legacy: Look for iframes in other TechInMind pages
            val iframes = doc.select("iframe[src]")
            iframes.forEach { iframe ->
                val iframeSrc = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                Log.d("TechInMind", "Found iframe: $iframeSrc")
                
                // If iframe directly contains multimoviesshg, extract it
                if (iframeSrc.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("TechInMind", "Found MultiMoviesShg iframe directly: $iframeSrc")
                    StreamHGExtractor().getUrl(iframeSrc, url, subtitleCallback, callback)
                    return
                }
                
                // If iframe contains ssn.techinmind, follow it for nested iframe
                if (iframeSrc.contains("ssn.techinmind.space", ignoreCase = true)) {
                    extractFromSSN(iframeSrc, url, subtitleCallback, callback)
                    return
                }
            }
            
            Log.e("TechInMind", "No video source found")
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Extract video from SSN TechInMind page
     * SSN pages contain iframe to multimoviesshg.com
     */
    private suspend fun extractFromSSN(
        ssnUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Extracting from SSN: $ssnUrl")
            val ssnDoc = app.get(ssnUrl, referer = referer).document
            
            // Find multimoviesshg iframe - check multiple selectors
            val videoIframe = ssnDoc.selectFirst("iframe#vidFrame, iframe[src*=multimoviesshg], iframe[allowfullscreen]")
            if (videoIframe != null) {
                val finalUrl = videoIframe.attr("abs:src").ifBlank { videoIframe.attr("src") }
                if (finalUrl.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("TechInMind", "Found MultiMoviesShg iframe in SSN: $finalUrl")
                    StreamHGExtractor().getUrl(finalUrl, ssnUrl, subtitleCallback, callback)
                    return
                }
            }
            
            // Fallback: Search for multimoviesshg URL in body HTML
            val bodyText = ssnDoc.body().html()
            val regex = Regex("""(https?://[^"'\s]*multimoviesshg[^"'\s]*/e/[a-zA-Z0-9]+)""")
            val match = regex.find(bodyText)
            if (match != null) {
                val multiMoviesUrl = match.groupValues[1]
                Log.d("TechInMind", "Found MultiMoviesShg URL in SSN body: $multiMoviesUrl")
                StreamHGExtractor().getUrl(multiMoviesUrl, ssnUrl, subtitleCallback, callback)
                return
            }
            
            Log.e("TechInMind", "No MultiMoviesShg URL found in SSN page")
        } catch (e: Exception) {
            Log.e("TechInMind", "Error extracting from SSN: ${e.message}")
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

// StreamHG Extractor - For multimoviesshg.com
class StreamHGExtractor : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamHG", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamHG", "Found M3U8: ${response.url}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction error: ${e.message}")
        }
    }
}

// EarnVids Extractor - For smoothpre.com
class EarnVidsExtractor : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("EarnVids", "Fetching: $url")
            
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("EarnVids", "Found M3U8: ${response.url}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("EarnVids", "Extraction error: ${e.message}")
        }
    }
}
