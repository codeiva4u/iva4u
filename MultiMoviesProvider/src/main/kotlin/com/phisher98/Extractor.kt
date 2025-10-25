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

// StreamHG (multimoviesshg.com) Extractor
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
            Log.d("StreamHG", "Extracting from: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract m3u8 or video source from StreamHG
            val script = doc.selectFirst("script:containsData(sources)")?.data()
            
            script?.let { scriptData ->
                val sourceRegex = Regex("""sources:\\s*\\[.*?file:\\s*["']([^"']+)["']""")
                val match = sourceRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    Log.d("StreamHG", "Found video: $it")
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            it,
                            linkType
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("StreamHG", "Error: ${e.message}")
        }
    }
}

// StreamP2P (multimovies.p2pplay.pro) Extractor
class StreamP2PExtractor : ExtractorApi() {
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
            Log.d("StreamP2P", "Extracting from: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video URL from StreamP2P
            val script = doc.selectFirst("script:containsData(file)")?.data()
            
            script?.let { scriptData ->
                val fileRegex = Regex("""file:\\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    Log.d("StreamP2P", "Found video: $it")
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            it,
                            linkType
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("StreamP2P", "Error: ${e.message}")
        }
    }
}

// RpmShare (multimovies.rpmhub.site) Extractor
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
            Log.d("RpmShare", "Extracting from: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video from RpmShare
            val script = doc.selectFirst("script:containsData(file)")?.data()
            
            script?.let { scriptData ->
                val fileRegex = Regex("""file:\\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    Log.d("RpmShare", "Found video: $it")
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            it,
                            linkType
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RpmShare", "Error: ${e.message}")
        }
    }
}

// UpnShare (server1.uns.bio) Extractor
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
            Log.d("UpnShare", "Extracting from: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video URL from UpnShare
            val script = doc.selectFirst("script:containsData(file)")?.data()
            
            script?.let { scriptData ->
                val fileRegex = Regex("""file:\\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    Log.d("UpnShare", "Found video: $it")
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            it,
                            linkType
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("UpnShare", "Error: ${e.message}")
        }
    }
}

// EarnVids/Smoothpre (smoothpre.com) Extractor
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
            Log.d("EarnVids", "Extracting from: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video source from EarnVids
            val script = doc.selectFirst("script:containsData(sources)")?.data()
            
            script?.let { scriptData ->
                val sourceRegex = Regex("""sources:\\s*\\[.*?file:\\s*["']([^"']+)["']""")
                val match = sourceRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    Log.d("EarnVids", "Found video: $it")
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            it,
                            linkType
                        ) {
                            this.referer = url
                            this.quality = Qualities.P720.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("EarnVids", "Error: ${e.message}")
        }
    }
}

// DDN GTXGamer (Download) Extractor
class DDNDownloadExtractor : ExtractorApi() {
    override val name = "DDNDownload"
    override val mainUrl = "https://ddn.gtxgamer.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("DDNDownload", "Processing download page: $url")
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract download button or direct link
            val downloadUrl = doc.selectFirst("a[href*='download']")?.attr("href")
                ?: doc.selectFirst("a.download-btn")?.attr("href")
            
            downloadUrl?.let {
                Log.d("DDNDownload", "Found download link: $it")
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("DDNDownload", "Error: ${e.message}")
        }
    }
}
