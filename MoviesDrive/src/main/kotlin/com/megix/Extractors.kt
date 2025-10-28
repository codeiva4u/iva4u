package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extract file ID from various Pixeldrain URL formats
            val mId = Regex("/(?:u|file|l)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
            
            if (mId.isNullOrEmpty()) {
                Log.d("PixelDrain", "Could not extract file ID from: $url")
                return
            }
            
            // Use direct API endpoint for better compatibility
            val finalUrl = "$mainUrl/api/file/$mId"
            
            // Get file info first to check if it's a valid video
            val infoResponse = app.get("$mainUrl/api/file/$mId/info", allowRedirects = false)
            if (infoResponse.isSuccessful) {
                val infoText = infoResponse.text
                val jsonInfo = JSONObject(infoText)
                val mimeType = jsonInfo.optString("mime_type", "")
                val fileName = jsonInfo.optString("name", "Video")
                val fileSize = jsonInfo.optLong("size", 0L)
                
                // Format size
                val formattedSize = if (fileSize < 1024L * 1024 * 1024) {
                    "%.2f MB".format(fileSize.toDouble() / (1024 * 1024))
                } else {
                    "%.2f GB".format(fileSize.toDouble() / (1024 * 1024 * 1024))
                }
                
                // Only process if it's a video file
                if (mimeType.startsWith("video/", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(
                            this.name, 
                            "${this.name} $fileName[$formattedSize]", 
                            finalUrl
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                } else {
                    Log.d("PixelDrain", "File is not a video. MIME type: $mimeType")
                }
            }
        } catch (e: Exception) {
            Log.e("PixelDrain", "Error extracting: ${e.message}")
        }
    }
}


open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.one"
    override val requiresReferer = false

    fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Validate URL before processing
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("null", ignoreCase = true)) {
            Log.d("HubCloud", "Invalid URL skipped: $url")
            return // Invalid URL or null source, skip processing
        }
        
        val doc = app.get(url).document

        var link = if (url.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.getOrNull(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if (link.startsWith("/")) {
            link = mainUrl + link
        }

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val div = document.selectFirst("div.card-body")

        div?.select("h2 a.btn")?.forEach {
            val btnLink = it.attr("href")
            val text = it.text()

            when {
                text.contains("Download [FSL Server]") -> {
                    callback.invoke(
                        newExtractorLink("$name[FSL Server]", "$name[FSL Server] $header[$size]", btnLink) {
                            quality = getIndexQuality(header)
                        }
                    )
                }

                text.contains("Download File") -> {
                    callback.invoke(
                        newExtractorLink(name, "$name $header[$size]", btnLink) {
                            quality = getIndexQuality(header)
                        }
                    )
                }

                text.contains("BuzzServer") -> {
                    val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                        .headers["hx-redirect"].orEmpty()
                    if (dlink.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "$name[BuzzServer]",
                                "$name[BuzzServer] $header[$size]",
                                getBaseUrl(btnLink) + dlink
                            ) {
                                quality = getIndexQuality(header)
                            }
                        )
                    }
                }

                btnLink.contains("pixeldra") -> {
                    try {
                        // Extract and use Pixeldrain properly
                        PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("HubCloud", "Pixeldrain extraction failed: ${e.message}")
                    }
                }

                text.contains("Download [Server : 10Gbps]") -> {
                    try {
                        // Follow the redirect chain to get final download link
                        val redirectResponse = app.get(btnLink, allowRedirects = false)
                        val locationHeader = redirectResponse.headers["location"] ?: return@forEach
                        
                        val dlink = if (locationHeader.contains("link=")) {
                            locationHeader.substringAfter("link=")
                        } else {
                            locationHeader
                        }
                        
                        // Validate final link
                        if (dlink.isBlank() || dlink.contains("null", ignoreCase = true)) {
                            Log.d("HubCloud", "Invalid 10Gbps link: $dlink")
                            return@forEach
                        }
                        
                        // Check if link is a valid video file or stream
                        if (dlink.matches(Regex(".*(mkv|mp4|avi|webm|m3u8).*", RegexOption.IGNORE_CASE))) {
                            callback.invoke(
                                newExtractorLink("$name[10Gbps]", "$name[10Gbps] $header[$size]", dlink) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        } else {
                            Log.d("HubCloud", "10Gbps link not a valid video file: $dlink")
                        }
                    } catch (e: Exception) {
                        Log.e("HubCloud", "Error processing 10Gbps link: ${e.message}")
                    }
                }

                else -> {
                    try {
                        loadExtractor(btnLink, "", subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("HubCloud", "LoadExtractor Error: ${e.localizedMessage}")
                    }
                }
            }
        }
    }
}

open class fastdlserver : ExtractorApi() {
    override val name: String = "fastdlserver"
    override var mainUrl = "https://pixel.hubcdn.fans/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

open class Gofile : ExtractorApi() {
    override val name: String = "Gofile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val contentId = url.substringAfterLast("/")
            val apiUrl = "$mainUrl/api/getContent?contentId=$contentId"
            
            val response = app.get(apiUrl).parsedSafe<GofileResponse>()
            response?.data?.contents?.values?.forEach { file ->
                if (file.mimetype?.startsWith("video/", ignoreCase = true) == true) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name ${file.name}",
                            file.link ?: return@forEach
                        ) {
                            this.quality = getIndexQuality(file.name ?: "")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Gofile", "Error extracting: ${e.message}")
        }
    }

    data class GofileResponse(
        val status: String?,
        val data: GofileData?
    )

    data class GofileData(
        val contents: Map<String, GofileContent>?
    )

    data class GofileContent(
        val name: String?,
        val mimetype: String?,
        val link: String?
    )
}

open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Validate URL before processing
            if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("null", ignoreCase = true)) {
                Log.d("GDFlix", "Invalid URL skipped: $url")
                return
            }
            
            val document = app.get(url).document
            
            // Extract video title and size from page
            val title = document.selectFirst("div.card-header")?.text() ?: "Video"
            val size = document.selectFirst("i#size")?.text() ?: ""
            
            // Find download buttons
            val downloadButtons = document.select("div.card-body h2 a.btn")
            
            downloadButtons.forEach { button ->
                val btnText = button.text()
                val btnLink = button.attr("href")
                
                when {
                    btnText.contains("Download", ignoreCase = true) && btnLink.isNotBlank() -> {
                        // Follow redirect to get actual download link
                        try {
                            val finalLink = if (btnLink.contains("gdflix", ignoreCase = true)) {
                                // Direct GDFlix link
                                val redirectResponse = app.get(btnLink, allowRedirects = false)
                                redirectResponse.headers["location"] ?: btnLink
                            } else {
                                btnLink
                            }
                            
                            if (finalLink.isNotBlank() && !finalLink.contains("null", ignoreCase = true)) {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name $title[$size]",
                                        finalLink
                                    ) {
                                        this.quality = getIndexQuality(title)
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Error processing download link: ${e.message}")
                        }
                    }
                    btnLink.contains("pixeldra", ignoreCase = true) -> {
                        try {
                            PixelDrain().getUrl(btnLink, url, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Pixeldrain extraction failed: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error extracting: ${e.message}")
        }
    }
}
