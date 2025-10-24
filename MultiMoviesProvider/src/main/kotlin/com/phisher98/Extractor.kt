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
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// StreamHG Extractor
open class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract m3u8 URL from page scripts
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("StreamHG", "Error: ${e.message}")
        }
    }
}

// StreamP2P Extractor
open class StreamP2P : ExtractorApi() {
    override var name = "StreamP2P"
    override var mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = url.replace("/#", "/e/")
            val response = app.get(embedUrl, referer = referer)
            val doc = response.document
            
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("StreamP2P", "Error: ${e.message}")
        }
    }
}

// RpmShare Extractor
open class RpmShare : ExtractorApi() {
    override var name = "RpmShare"
    override var mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = url.replace("/#", "/e/")
            val response = app.get(embedUrl, referer = referer)
            val doc = response.document
            
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("RpmShare", "Error: ${e.message}")
        }
    }
}

// UpnShare Extractor
open class UpnShare : ExtractorApi() {
    override var name = "UpnShare"
    override var mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = url.replace("/#", "/e/")
            val response = app.get(embedUrl, referer = referer)
            val doc = response.document
            
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("UpnShare", "Error: ${e.message}")
        }
    }
}

// EarnVids (SmoothPre) Extractor
open class EarnVids : ExtractorApi() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("EarnVids", "Error: ${e.message}")
        }
    }
}

// GDMirrorBot Extractor
open class GDMirrorBot : ExtractorApi() {
    override var name = "GDMirrorBot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Get all server links from the modal
            doc.select("li.server-item[data-link]").forEach { element ->
                val serverLink = element.attr("data-link")
                val serverName = element.selectFirst(".server-name")?.text() ?: name
                
                if (serverLink.isNotEmpty()) {
                    // Recursively extract from sub-hosters
                    try {
                        loadExtractor(serverLink, referer, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("GDMirrorBot", "Error loading $serverName: ${e.message}")
                    }
                }
            }
            
            // Get download link - Temporarily commented due to deprecated API
            // doc.select("a.dlvideoLinks").forEach { element ->
            //     val downloadUrl = element.attr("href")
            //     if (downloadUrl.isNotEmpty()) {
            //         callback.invoke(
            //             ExtractorLink(
            //                 "$name - Download",
            //                 "Download",
            //                 downloadUrl,
            //                 referer ?: mainUrl,
            //                 Qualities.Unknown.value
            //             )
            //         )
            //     }
            // }
        } catch (e: Exception) {
            Log.e("GDMirrorBot", "Error: ${e.message}")
        }
    }
}

// TechInMind Extractor (for TV shows)
open class TechInMind : ExtractorApi() {
    override var name = "TechInMind"
    override var mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            val scriptText = doc.selectFirst("script:containsData(sources)")?.data()
            if (scriptText != null) {
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Url = m3u8Regex.find(scriptText)?.groupValues?.get(1)
                
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer ?: mainUrl
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            Log.e("TechInMind", "Error: ${e.message}")
        }
    }
}
