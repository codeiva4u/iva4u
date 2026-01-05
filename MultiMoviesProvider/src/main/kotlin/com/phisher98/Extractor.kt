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
import com.lagradost.cloudstream3.utils.JsUnpacker
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

// GdMirror/GtxGamer Extractor - Handles 5GDL menu page
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
            
            val latestUrl = getLatestUrl(url, "gdmirror")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val response = app.get(newUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url

            // 1. Parse streaming server items (5GDL menu) - data-link elements only
            doc.select("li.server-item[data-link]").forEach { item ->
                val link = item.attr("data-link")
                val text = item.text()
                
                if (link.isNotBlank() && !link.startsWith("#")) {
                    Log.d("GdMirror", "Found streaming server: $text -> $link")
                    
                    // Route to appropriate extractor based on URL patterns
                    when {
                        link.contains("multimoviesshg", true) -> {
                            StreamHGExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        link.contains("smoothpre", true) -> {
                            EarnVidsExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        link.contains("p2pplay", true) -> {
                            StreamP2PExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        link.contains("uns.bio", true) -> {
                            UpnShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        link.contains("rpmhub", true) -> {
                            RpmShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        // Fallback based on text labels
                        text.contains("StreamHG", true) || text.contains("SMWH", true) -> {
                            StreamHGExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        text.contains("EarnVids", true) || text.contains("FLLS", true) -> {
                            EarnVidsExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        text.contains("StreamP2P", true) || text.contains("STRMP2", true) -> {
                            StreamP2PExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        text.contains("UpnShare", true) || text.contains("UPNSHR", true) -> {
                            UpnShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        text.contains("RpmShare", true) || text.contains("RPMSHRE", true) -> {
                            RpmShareExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                        else -> {
                            // For unknown servers, try StreamHG as default
                            Log.d("GdMirror", "Unknown server, trying StreamHG: $link")
                            StreamHGExtractor().getUrl(link, finalUrl, subtitleCallback, callback)
                        }
                    }
                }
            }

            // 2. Check for default iframe (usually StreamHG)
            doc.select("iframe[src], iframe#vidFrame").forEach { iframe ->
                val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                if (src.isNotBlank() && src.contains("multimoviesshg", ignoreCase = true)) {
                    Log.d("GdMirror", "Found StreamHG iframe: $src")
                    StreamHGExtractor().getUrl(src, finalUrl, subtitleCallback, callback)
                }
            }
            
            // 3. Fallback: Search for multimoviesshg URL in page HTML
            val bodyText = doc.html()
            val streamHgRegex = Regex("""(https?://[^"'\s]*multimoviesshg[^"'\s]*/e/[a-zA-Z0-9]+)""")
            streamHgRegex.find(bodyText)?.let {
                Log.d("GdMirror", "Found StreamHG URL in HTML: ${it.groupValues[1]}")
                StreamHGExtractor().getUrl(it.groupValues[1], finalUrl, subtitleCallback, callback)
            }
            
            // NOTE: We intentionally do NOT call loadExtractor for download links
            // to prevent built-in extractors (VidHidePro, Vidstack) from being used

        } catch (e: Exception) {
            Log.e("GdMirror", "Fatal extraction error: ${e.message}")
        }
    }
}

// TechInMind Extractor - Handles stream.techinmind.space and ssn.techinmind.space
// Chain: stream.techinmind.space → ssn.techinmind.space → multimoviesshg.com
class TechInMindExtractor : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Starting extraction for: $url")
            val ref = referer ?: url
            
            val doc = app.get(url, referer = ref).document
            
            // Step 1: If on stream.techinmind.space/embed, find ssn.techinmind.space iframe
            if (url.contains("stream.techinmind.space")) {
                val ssnIframe = doc.selectFirst("iframe[src*=ssn.techinmind]")
                if (ssnIframe != null) {
                    val ssnUrl = ssnIframe.attr("abs:src").ifBlank { ssnIframe.attr("src") }
                    Log.d("TechInMind", "Found SSN iframe: $ssnUrl")
                    extractFromSSN(ssnUrl, url, subtitleCallback, callback)
                    return
                }
                
                // Check for data-link elements
                doc.select("a[data-link]").forEach { link ->
                    val dataLink = link.attr("data-link")
                    if (dataLink.contains("ssn.techinmind")) {
                        Log.d("TechInMind", "Found SSN data-link: $dataLink")
                        extractFromSSN(dataLink, url, subtitleCallback, callback)
                    }
                }
            }
            
            // Step 2: If already on ssn.techinmind.space, extract directly
            if (url.contains("ssn.techinmind.space")) {
                extractFromSSN(url, ref, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction error: ${e.message}")
        }
    }
    
    private suspend fun extractFromSSN(
        ssnUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Extracting from SSN: $ssnUrl")
            val ssnDoc = app.get(ssnUrl, referer = referer).document
            
            // Find video iframe - could be any hoster
            val videoIframe = ssnDoc.selectFirst("iframe#vidFrame, iframe[allowfullscreen]")
            if (videoIframe != null) {
                val iframeSrc = videoIframe.attr("abs:src").ifBlank { videoIframe.attr("src") }
                Log.d("TechInMind", "Found video iframe: $iframeSrc")
                
                // Route to appropriate extractor based on URL
                when {
                    iframeSrc.contains("multimoviesshg", true) -> {
                        StreamHGExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                    iframeSrc.contains("uns.bio", true) -> {
                        UpnShareExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                    iframeSrc.contains("p2pplay", true) -> {
                        StreamP2PExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                    iframeSrc.contains("smoothpre", true) -> {
                        EarnVidsExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                    iframeSrc.contains("rpmhub", true) -> {
                        RpmShareExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                    else -> {
                        // For unknown hosters, try StreamHG as fallback
                        Log.d("TechInMind", "Unknown hoster, trying StreamHG: $iframeSrc")
                        StreamHGExtractor().getUrl(iframeSrc, ssnUrl, subtitleCallback, callback)
                    }
                }
                return
            }
            
            Log.e("TechInMind", "No video iframe found in SSN page")
            
        } catch (e: Exception) {
            Log.e("TechInMind", "Error extracting from SSN: ${e.message}")
        }
    }
}// RpmShare Extractor
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
            Log.d("StreamHG", "Starting extraction for: $url")
            val ref = referer ?: url
            
            // Method 1: Get page source and try JsUnpacker first (for packed JavaScript)
            val pageText = app.get(url, referer = ref).text
            Log.d("StreamHG", "Got page text, length: ${pageText.length}")
            
            // Try JsUnpacker first
            JsUnpacker(pageText).unpack()?.let { unpacked ->
                Log.d("StreamHG", "Unpacked JS successfully")
                val m3u8Regex = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                m3u8Regex.find(unpacked)?.let { match ->
                    val m3u8Url = match.groupValues[1]
                    Log.d("StreamHG", "Found M3U8 from JsUnpacker: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(name, "$name [Unpacked]", m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = ref
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            }
            
            // Try direct regex on page source
            Regex("""(https?://[^"'\s]+master\.m3u8[^"'\s]*)""").find(pageText)?.let {
                Log.d("StreamHG", "Found M3U8 from direct regex: ${it.groupValues[1]}")
                callback.invoke(
                    newExtractorLink(name, "$name [Regex]", it.groupValues[1], ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }
            
            // Method 2: WebViewResolver fallback
            Log.d("StreamHG", "Trying WebViewResolver fallback...")
            val response = app.get(url, referer = ref, interceptor = WebViewResolver(Regex("""(master|playlist|index)\.m3u8""")))
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamHG", "Found M3U8 from WebViewResolver: ${response.url}")
                callback.invoke(
                    newExtractorLink(name, "$name [WebView]", response.url, ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }
            
            Log.e("StreamHG", "No M3U8 URL found")
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction error: ${e.message}")
            e.printStackTrace()
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
            val ref = referer ?: url

            val response = app.get(
                url,
                referer = ref,
                interceptor = WebViewResolver(
                    Regex("""(master|playlist|index)\.m3u8""")
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
                        this.referer = ref
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }

            // Fallback: Check for other m3u8 via text or unpacking
            val text = app.get(url, referer = ref).text
             // Try standard m3u8 regex
            Regex("""(https?://.*?\.m3u8.*?)["']""").find(text)?.let {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [Regex]",
                        it.groupValues[1],
                        ExtractorLinkType.M3U8
                    ) {
                         this.referer = ref
                         this.quality = Qualities.P1080.value
                    }
                )
                return
            }
             // Try unpacking 
             JsUnpacker(text).unpack()?.let { unpacked ->
                 Regex("""(https?://.*?\.m3u8.*?)["']""").find(unpacked)?.let {
                     callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [Unpacked]",
                            it.groupValues[1],
                            ExtractorLinkType.M3U8
                        ) {
                             this.referer = ref
                             this.quality = Qualities.P1080.value
                        }
                    )
                 }
             }

        } catch (e: Exception) {
            Log.e("EarnVids", "Extraction error: ${e.message}")
        }
    }
}
