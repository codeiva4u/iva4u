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
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video URL from Gofile page
            val videoUrl = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("a[download]")?.attr("href")
            
            videoUrl?.let {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.d("GofileExtractor", "Error: ${e.message}")
        }
    }
}

// FilePress Extractor
class FilePressExtractor : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filepress.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract download link from FilePress
            val downloadUrl = doc.selectFirst("a.btn-download")?.attr("href")
                ?: doc.selectFirst("a[href*='download']")?.attr("href")
            
            downloadUrl?.let {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.d("FilePressExtractor", "Error: ${e.message}")
        }
    }
}

// StreamWish (StreamHG) Extractor  
class StreamWishExtractor : ExtractorApi() {
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
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract m3u8 or video source from StreamWish
            val script = doc.selectFirst("script:containsData(sources)")?.data()
            
            script?.let { scriptData ->
                val sourceRegex = Regex("""sources:\s*\[.*?file:\s*["']([^"']+)["']""")
                val match = sourceRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                }
            }
        } catch (e: Exception) {
            Log.d("StreamWishExtractor", "Error: ${e.message}")
        }
    }
}

// StreamP2P Extractor
class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://p2pplay.pro"
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
            
            // Extract video URL from StreamP2P
            val script = doc.selectFirst("script:containsData(file)")?.data()
            
            script?.let { scriptData ->
                val fileRegex = Regex("""file:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                }
            }
        } catch (e: Exception) {
            Log.d("StreamP2PExtractor", "Error: ${e.message}")
        }
    }
}

// VidHide (ErnVids) Extractor
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
            val response = app.get(url, referer = referer)
            val doc = response.document
            
            // Extract video source from VidHide
            val script = doc.selectFirst("script:containsData(sources)")?.data()
            
            script?.let { scriptData ->
                val sourceRegex = Regex("""sources:\s*\[.*?file:\s*["']([^"']+)["']""")
                val match = sourceRegex.find(scriptData)
                val videoUrl = match?.groupValues?.get(1)
                
                videoUrl?.let {
                    val linkType = if (it.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it,
                        linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                }
            }
        } catch (e: Exception) {
            Log.d("VidHideExtractor", "Error: ${e.message}")
        }
    }
}

// GDMirrorBot Extractor (intermediate page)
class GDMirrorBotExtractor : ExtractorApi() {
    override val name = "GDMirrorBot"
    override val mainUrl = "https://gdmirrorbot.nl"
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
            
            // Extract iframe source
            val iframeSrc = doc.selectFirst("iframe#vidFrame")?.attr("src")
            
            iframeSrc?.let {
                // This will be handled by other extractors
                Log.d("GDMirrorBotExtractor", "Found iframe: $it")
            }
        } catch (e: Exception) {
            Log.d("GDMirrorBotExtractor", "Error: ${e.message}")
        }
    }
}

// LoadMyFile Extractor (download page)
class LoadMyFileExtractor : ExtractorApi() {
    override val name = "LoadMyFile"
    override val mainUrl = "https://loadmyfile.com"
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
            
            // Extract all download server links
            val links = doc.select("a[href*='igx.gtxgamer.site']")
            
            links.forEach { link ->
                val downloadUrl = link.attr("href")
                Log.d("LoadMyFileExtractor", "Found download link: $downloadUrl")
            }
        } catch (e: Exception) {
            Log.d("LoadMyFileExtractor", "Error: ${e.message}")
        }
    }
}