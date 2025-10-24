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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// StreamHG Extractor
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
            Log.d("StreamHG", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamHG", "M3U8 extraction successful: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction error: ${e.message}")
        }
    }
}

// RpmShare Extractor
class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("RpmShare", "M3U8 extraction successful: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("RpmShare", "Extraction error: ${e.message}")
        }
    }
}

// UpnShare Extractor
class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UpnShare", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("UpnShare", "M3U8 extraction successful: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("UpnShare", "Extraction error: ${e.message}")
        }
    }
}

// SmoothPre/EarnVids Extractor
class SmoothPreExtractor : ExtractorApi() {
    override val name = "SmoothPre"
    override val mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("SmoothPre", "Starting extraction for URL: $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("SmoothPre", "M3U8 extraction successful: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("SmoothPre", "Extraction error: ${e.message}")
        }
    }
}

// GTXGamer Download Extractor  
class GTXGamerExtractor : ExtractorApi() {
    override val name = "GTXGamer"
    override val mainUrl = "https://ddn.gtxgamer.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // GTXGamer is a download link provider, use built-in CloudStream extractors
        loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}
