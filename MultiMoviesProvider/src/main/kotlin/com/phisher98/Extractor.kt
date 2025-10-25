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
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// StreamWish Extractor - Handles multimoviesshg.com, streamwish.com, awish.pro
open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamWish", "Starting extraction for: $url")
            
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamWish", "M3U8 found: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    referer ?: url
                ).forEach(callback)
            } else {
                Log.e("StreamWish", "No M3U8 URL found")
            }
        } catch (e: Exception) {
            Log.e("StreamWish", "Extraction error: ${e.message}")
        }
    }
}

// VidHide Extractor - Handles vidhide.com, vidhidepro.com
open class VidHideExtractor : ExtractorApi() {
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
            Log.d("VidHide", "Starting extraction for: $url")
            
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("VidHide", "M3U8 found: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    referer ?: url
                ).forEach(callback)
            } else {
                Log.e("VidHide", "No M3U8 URL found")
            }
        } catch (e: Exception) {
            Log.e("VidHide", "Extraction error: ${e.message}")
        }
    }
}

// StreamP2P Extractor - Handles multimovies.p2pplay.pro
open class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamP2P", "Starting extraction for: $url")
            
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|\.m3u8)""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                Log.d("StreamP2P", "M3U8 found: ${response.url}")
                M3u8Helper.generateM3u8(
                    name,
                    response.url,
                    referer ?: url
                ).forEach(callback)
            } else {
                Log.e("StreamP2P", "No M3U8 URL found")
            }
        } catch (e: Exception) {
            Log.e("StreamP2P", "Extraction error: ${e.message}")
        }
    }
}
