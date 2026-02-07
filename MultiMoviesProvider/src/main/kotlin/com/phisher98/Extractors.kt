package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════════
// MULTIMOVIES EXTRACTORS
// Working Mirrors: Swish, Rpmshare, Streamp2p, Upnshare, Flion
// ═══════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return URI(url).let { "${it.scheme}://${it.host}" }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ═══════════════════════════════════════════════════════════════════════════════
// GDMIRROR DOWNLOAD - Main extractor for ddn.iqsmartgames.com
// ═══════════════════════════════════════════════════════════════════════════════
class GDMirrorDownload : ExtractorApi() {
    override val name = "GDMirror"
    override val mainUrl = "https://ddn.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDMirror", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val html = response.text
            val finalUrl = response.url
            
            // Extract direct Workers.dev URL
            val fileurlMatch = Regex("""const\s+fileurl\s*=\s*["']([^"']+)["']""").find(html)
            val directUrl = fileurlMatch?.groupValues?.get(1)?.replace("\\/", "/")
            
            val filenameMatch = Regex("""const\s+filename\s*=\s*["']([^"']+)["']""").find(html)
            val fileName = filenameMatch?.groupValues?.get(1)
            
            if (!directUrl.isNullOrEmpty()) {
                Log.d("GDMirror", "Direct URL: $directUrl")
                callback.invoke(
                    newExtractorLink(
                        "GDMirror[Direct]",
                        "GDMirror[Direct] ${fileName ?: ""}",
                        directUrl
                    ) {
                        this.quality = getIndexQuality(fileName)
                    }
                )
            }
            
            // Extract mirror vpage links
            val doc = response.document
            val mirrorItems = doc.select(".mirror-item")
            
            mirrorItems.amap { mirror ->
                val mirrorName = mirror.select(".mirror-name strong, .mirror-name").text().trim()
                val vpageLink = mirror.select("a[href*=vpage]").attr("href")
                
                // Only process: Swish, Rpmshare, Streamp2p, Upnshare, Flion
                if (vpageLink.isNotEmpty() && isWorkingMirror(mirrorName)) {
                    Log.d("GDMirror", "Processing mirror: $mirrorName")
                    try {
                        extractMirror(vpageLink, mirrorName, fileName, finalUrl, callback)
                    } catch (e: Exception) {
                        Log.e("GDMirror", "$mirrorName failed: ${e.message}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("GDMirror", "Extraction failed: ${e.message}")
        }
    }
    
    private fun isWorkingMirror(name: String): Boolean {
        val workingMirrors = listOf("Swish", "Rpmshare", "Streamp2p", "Upnshare", "Flion")
        return workingMirrors.any { name.contains(it, ignoreCase = true) }
    }
    
    private suspend fun extractMirror(
        vpageUrl: String,
        mirrorName: String,
        fileName: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(vpageUrl, referer = referer, allowRedirects = true)
        val finalUrl = response.url
        val doc = response.document
        
        Log.d("GDMirror", "$mirrorName -> $finalUrl")
        
        // Direct video file URL
        if (finalUrl.endsWith(".mkv") || finalUrl.endsWith(".mp4")) {
            emitLink(finalUrl, mirrorName, fileName, callback)
            return
        }
        
        // Find download link on page
        val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download), a.btn-download").attr("href")
        if (downloadLink.isNotEmpty()) {
            val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "${getBaseUrl(finalUrl)}$downloadLink"
            emitLink(fullUrl, mirrorName, fileName, callback)
        }
    }
    
    private suspend fun emitLink(url: String, mirrorName: String, fileName: String?, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                "GDMirror[$mirrorName]",
                "GDMirror[$mirrorName] ${fileName ?: ""}",
                url
            ) {
                this.quality = getIndexQuality(fileName)
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SWISH EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class SwishExtractor : ExtractorApi() {
    override val name = "Swish"
    override val mainUrl = "https://swish.ink"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Swish", "Extracting: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Swish", "Failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RPMSHARE EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class RpmshareExtractor : ExtractorApi() {
    override val name = "Rpmshare"
    override val mainUrl = "https://rpmshare.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Rpmshare", "Extracting: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.btn-download, a:contains(Download)").attr("href")
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Rpmshare", "Failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMP2P EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class Streamp2pExtractor : ExtractorApi() {
    override val name = "Streamp2p"
    override val mainUrl = "https://streamp2p.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Streamp2p", "Extracting: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download, a:contains(Download)").attr("href")
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Streamp2p", "Failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UPNSHARE EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class UpnshareExtractor : ExtractorApi() {
    override val name = "Upnshare"
    override val mainUrl = "https://upnshare.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Upnshare", "Extracting: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Upnshare", "Failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FLION EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class FlionExtractor : ExtractorApi() {
    override val name = "Flion"
    override val mainUrl = "https://flion.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Flion", "Extracting: $url")
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Flion", "Failed: ${e.message}")
        }
    }
}
