package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════════
// MULTIMOVIES EXTRACTORS - MIRROR DOWNLOAD LINKS
// Mirrors: Gofile, Fpress, Swish, Rpmshare, Streamp2p, Upnshare, Flion
// (Gdtot excluded)
// ═══════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return URI(url).let { "${it.scheme}://${it.host}" }
}

// Quality detection from filename
fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ═══════════════════════════════════════════════════════════════════════════════
// GDMIRROR DOWNLOAD EXTRACTOR - Main page with all mirrors
// Handles: ddn.iqsmartgames.com/file/{slug} and /files/{encrypted}
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
            
            // Extract fileurl (Workers.dev direct link)
            val fileurlMatch = Regex("""const\s+fileurl\s*=\s*["']([^"']+)["']""").find(html)
            val directUrl = fileurlMatch?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
            
            // Extract filename
            val filenameMatch = Regex("""const\s+filename\s*=\s*["']([^"']+)["']""").find(html)
            val fileName = filenameMatch?.groupValues?.get(1)
            
            // Emit direct Workers.dev link
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
            
            // Extract all mirror vpage links
            val doc = response.document
            val mirrorItems = doc.select(".mirror-item")
            
            mirrorItems.amap { mirror ->
                val mirrorName = mirror.select(".mirror-name strong, .mirror-name").text().trim()
                val vpageLink = mirror.select("a[href*=vpage]").attr("href")
                
                if (vpageLink.isNotEmpty() && !mirrorName.contains("Gdtot", true)) {
                    Log.d("GDMirror", "Mirror: $mirrorName -> $vpageLink")
                    
                    try {
                        extractVPageMirror(vpageLink, mirrorName, fileName, finalUrl, callback)
                    } catch (e: Exception) {
                        Log.e("GDMirror", "Mirror $mirrorName failed: ${e.message}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("GDMirror", "Extraction failed: ${e.message}")
        }
    }
    
    private suspend fun extractVPageMirror(
        vpageUrl: String,
        mirrorName: String,
        fileName: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(vpageUrl, referer = referer, allowRedirects = true)
            val finalUrl = response.url
            val html = response.text
            
            Log.d("GDMirror", "$mirrorName redirected to: $finalUrl")
            
            // Check for direct download in final URL
            if (finalUrl.contains(".mkv") || finalUrl.contains(".mp4")) {
                emitLink(finalUrl, mirrorName, fileName, callback)
                return
            }
            
            val doc = response.document
            
            // Different mirror patterns
            when {
                // Gofile
                finalUrl.contains("gofile.io") -> {
                    val downloadBtn = doc.select("a[href*=download], button[onclick*=download]").attr("href")
                    if (downloadBtn.isNotEmpty()) {
                        emitLink(downloadBtn, mirrorName, fileName, callback)
                    }
                }
                
                // Fpress
                finalUrl.contains("fpress") -> {
                    val downloadLink = doc.select("a.download-btn, a[href*=download], a:contains(Download)").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "${getBaseUrl(finalUrl)}$downloadLink"
                        emitLink(fullUrl, mirrorName, fileName, callback)
                    }
                }
                
                // Swish
                finalUrl.contains("swish") || mirrorName.contains("Swish", true) -> {
                    val downloadLink = doc.select("a.download-btn, a[href*=download]").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        emitLink(downloadLink, mirrorName, fileName, callback)
                    }
                }
                
                // Rpmshare
                finalUrl.contains("rpmshare") || mirrorName.contains("Rpmshare", true) -> {
                    val downloadLink = doc.select("a[href*=download], a.btn-download").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        emitLink(downloadLink, mirrorName, fileName, callback)
                    }
                }
                
                // Streamp2p
                finalUrl.contains("p2p") || mirrorName.contains("Streamp2p", true) -> {
                    val downloadLink = doc.select("a[href*=download], a.download").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        emitLink(downloadLink, mirrorName, fileName, callback)
                    }
                }
                
                // Upnshare
                finalUrl.contains("upnshare") || finalUrl.contains("uns.bio") || mirrorName.contains("Upnshare", true) -> {
                    val downloadLink = doc.select("a[href*=download], a.download-btn").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        emitLink(downloadLink, mirrorName, fileName, callback)
                    }
                }
                
                // Flion
                finalUrl.contains("flion") || mirrorName.contains("Flion", true) -> {
                    val downloadLink = doc.select("a[href*=download], a.download-btn").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        emitLink(downloadLink, mirrorName, fileName, callback)
                    }
                }
                
                // Buzzheavier
                finalUrl.contains("buzzheavier") -> {
                    val downloadLink = doc.select("a[href*=download], a:contains(Download)").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "https://buzzheavier.com$downloadLink"
                        emitLink(fullUrl, mirrorName, fileName, callback)
                    }
                }
                
                // Fallback: try to find any download link
                else -> {
                    val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
                    if (downloadLink.isNotEmpty()) {
                        val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "${getBaseUrl(finalUrl)}$downloadLink"
                        emitLink(fullUrl, mirrorName, fileName, callback)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("GDMirror", "VPage extraction failed for $mirrorName: ${e.message}")
        }
    }
    
    private suspend fun emitLink(
        url: String,
        mirrorName: String,
        fileName: String?,
        callback: (ExtractorLink) -> Unit
    ) {
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
// GOFILE EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class GofileExtractor : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Gofile", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document
            
            // Look for download link
            val downloadLink = doc.select("a[href*=download], button[data-url]").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        downloadLink
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Gofile", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FPRESS EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class FpressExtractor : ExtractorApi() {
    override val name = "Fpress"
    override val mainUrl = "https://fpress.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Fpress", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a.download-btn, a[href*=download], a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Fpress", "Extraction failed: ${e.message}")
        }
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
            Log.d("Swish", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a.download-btn, a[href*=download], a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Swish", "Extraction failed: ${e.message}")
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
            Log.d("Rpmshare", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.btn-download, a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Rpmshare", "Extraction failed: ${e.message}")
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
            Log.d("Streamp2p", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download, a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Streamp2p", "Extraction failed: ${e.message}")
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
            Log.d("Upnshare", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Upnshare", "Extraction failed: ${e.message}")
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
            Log.d("Flion", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a.download-btn, a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Flion", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BUZZHEAVIER EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
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
            Log.d("Buzzheavier", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        fullUrl
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Buzzheavier", "Extraction failed: ${e.message}")
        }
    }
}
