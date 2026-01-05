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
            
            val latestUrl = getLatestUrl(url, "gdmirror")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val response = app.get(newUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url

            // 1. Parse split servers (5GDL menu) - Priority
            // Selector: li.server-item with data-link
            doc.select("li.server-item").forEach { item ->
                val link = item.attr("data-link")
                val text = item.text()
                val serverName = item.select(".server-name").text()
                val serverMeta = item.select(".server-meta").text()
                
                if (link.isNotBlank() && !link.startsWith("#")) {
                    Log.d("GdMirror", "Found server: $serverName ($serverMeta) -> $link")
                    
                    when {
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
                    }
                }
            }

            // 2. Check for default iframe (usually StreamHG)
            doc.select("iframe[src], iframe#vidFrame").forEach { iframe ->
                val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                if (src.isNotBlank()) {
                     Log.d("GdMirror", "Found iframe: $src")
                     if (src.contains("multimoviesshg", ignoreCase = true)) {
                        StreamHGExtractor().getUrl(src, finalUrl, subtitleCallback, callback)
                     }
                }
            }
            
            // 3. Fallback to hidden inputs or scripts
            val bodyText = doc.html()
            val streamHgRegex = Regex("""(https?://[^"'\s]*multimoviesshg[^"'\s]*/e/[a-zA-Z0-9]+)""")
            streamHgRegex.find(bodyText)?.let {
                 StreamHGExtractor().getUrl(it.groupValues[1], finalUrl, subtitleCallback, callback)
            }

        } catch (e: Exception) {
            Log.e("GdMirror", "Fatal extraction error: ${e.message}")
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

            // Fallback: Manually check for m3u8 in page content
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
