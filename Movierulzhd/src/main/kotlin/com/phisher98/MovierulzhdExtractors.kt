package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

// Daddylive - Simple iframe-based extractor
class Daddylive : ExtractorApi() {
    override val name = "Daddylive"
    override val mainUrl = "https://daddylive.futbol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        
        // Look for iframe source
        doc.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(iframeSrc, url, subtitleCallback, callback)
            }
        }
        
        // Look for direct video URLs
        doc.select("source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotEmpty()) {
                val quality = Regex("(\\d{3,4})[pP]").find(source.attr("label"))?.groupValues?.get(1)?.toIntOrNull() 
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
    }
}

// DeadDrive - Complex extractor with multiple iframes
class DeadDrive : ExtractorApi() {
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
        
        // Extract server links from the list
        doc.select("ul.list-server-items > li").forEach { server ->
            val videoUrl = server.attr("data-video")
            if (videoUrl.isNotEmpty()) {
                loadExtractor(videoUrl, url, subtitleCallback, callback)
            }
        }
    }
}

// GdMirrorBot - Encrypted/Obfuscated extractor
class GdMirrorBot : ExtractorApi() {
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
            Log.d("Movierulzhd", "$host/embedhelper.php")
            
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
                Log.d("Movierulzhd", href)
                
                loadExtractor(href, mainUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("Movierulzhd", "Error in GdMirrorBot extractor: ${e.message}")
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url
        }
    }
} 