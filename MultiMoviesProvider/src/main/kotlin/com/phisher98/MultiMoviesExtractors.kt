package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// GdMirrorBot - Encrypted/Obfuscated extractor for MultiMovies
class MultiGdMirrorBot : ExtractorApi() {
    override val name = "GdMirrorBot"
    override val mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val host = getBaseUrl(app.get(url).url)
            val embed = url.substringAfterLast("/")
            val data = mapOf("sid" to embed)
            
            val jsonString = app.post("$host/embedhelper.php", data = data).text
            Log.d("MultiMovies", "$host/embedhelper.php")
            
            // Parse JSON response
            val jsonElement = JsonParser.parseString(jsonString)
            if (!jsonElement.isJsonObject) {
                Log.e("Error:", "Unexpected JSON format: Response is not a JSON object")
                return
            }
            
            val jsonObject = jsonElement.asJsonObject
            val siteUrls = jsonObject["siteUrls"]?.takeIf { it.isJsonObject }?.asJsonObject
            val mresultEncoded = jsonObject["mresult"]?.takeIf { it.isJsonPrimitive }?.asString
            
            val mresult = mresultEncoded?.let {
                val decodedString = base64Decode(it)
                JsonParser.parseString(decodedString).asJsonObject
            }
            
            val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.takeIf { it.isJsonObject }?.asJsonObject
            
            if (siteUrls == null || siteFriendlyNames == null || mresult == null) {
                Log.e("Error:", "Missing required JSON fields in response")
                return
            }
            
            // Find common keys and process them
            val commonKeys = siteUrls.keySet().intersect(mresult.keySet())
            commonKeys.forEach { key ->
                val siteName = siteFriendlyNames[key]?.asString ?: return@forEach
                val siteUrl = siteUrls[key]?.asString ?: return@forEach
                val resultUrl = mresult[key]?.asString ?: return@forEach
                
                val href = siteUrl + resultUrl
                Log.d("MultiMovies", href)
                
                loadExtractor(href, mainUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("MultiMovies", "Error in GdMirrorBot extractor: ${e.message}")
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}

// DeadDrive - Complex extractor with multiple iframes
class MultiDeadDrive : ExtractorApi() {
    override val name = "DeadDrive"
    override val mainUrl = "https://deaddrive.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        
        // Extract video links from server items
        doc.select("ul.list-server-items > li").forEach { server ->
            val videoUrl = server.attr("data-video")
            if (videoUrl.isNotEmpty()) {
                loadExtractor(videoUrl, url, subtitleCallback, callback)
            }
        }
    }
}

// StreamSB wrapper - handles StreamSB embeds
class MultiStreamSB : ExtractorApi() {
    override val name = "StreamSB"
    override val mainUrl = "https://cloudemb.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Simply load the StreamSB extractor that's built into Cloudstream3
        loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

// Generic direct link extractor for common embeds
class MultiDirectLink : ExtractorApi() {
    override val name = "Direct"
    override val mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        
        // Look for direct video sources
        doc.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotEmpty()) {
                val quality = Regex("(\\d{3,4})[pP]").find(source.attr("label") ?: "")?.groupValues?.get(1)?.toIntOrNull() 
                    ?: Qualities.Unknown.value
                
                val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = type
                    ) {
                        this.headers = mapOf("Referer" to url)
                        this.quality = quality
                    }
                )
            }
        }
        
        // Look for m3u8 URLs in script tags
        val scriptContent = doc.select("script").joinToString(" ") { it.html() }
        Regex("['\"](https?://[^'\"]+\\.m3u8[^\'\"]*)").findAll(scriptContent).forEach { match ->
            val m3u8Url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name M3U8",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to url)
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
} 