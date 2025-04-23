package com.phisher98

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MultimoviesAIO: StreamWishExtractor() {
    override var name = "Multimovies Cloud AIO"
    override var mainUrl = "https://allinonedownloader.fun"
    override var requiresReferer = true
}

class Multimovies: StreamWishExtractor() {
    override var name = "Multimovies Cloud"
    override var mainUrl = "https://multimovies.cloud"
    override var requiresReferer = true
}

class Animezia : VidhideExtractor() {
    override var name = "Animezia"
    override var mainUrl = "https://animezia.cloud"
    override var requiresReferer = true
}

class server2 : VidhideExtractor() {
    override var name = "Multimovies Vidhide"
    override var mainUrl = "https://server2.shop"
    override var requiresReferer = true
}

class Asnwish : StreamWishExtractor() {
    override val name = "Streanwish Asn"
    override val mainUrl = "https://asnwish.com"
    override val requiresReferer = true
}

class CdnwishCom : StreamWishExtractor() {
    override val name = "Cdnwish"
    override val mainUrl = "https://cdnwish.com"
    override val requiresReferer = true
}

class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Phisher", "Processing GDMirrorbot URL: $url")
            
            val host = getBaseUrl(app.get(url).url)
            val embed = url.substringAfterLast("/")
            val data = mapOf("sid" to embed)
            val response = app.post("$host/embedhelper.php", data = data)
            val jsonString = response.text
            Log.d("Phisher", "GDMirrorbot response: $jsonString")
            
            if (jsonString.isEmpty()) {
                Log.e("Error:", "Empty response from GDMirrorbot")
                return
            }
            
            val jsonElement: JsonElement = JsonParser.parseString(jsonString)
            if (!jsonElement.isJsonObject) {
                Log.e("Error:", "Unexpected JSON format: Response is not a JSON object")
                return
            }
            
            val jsonObject = jsonElement.asJsonObject
            
            // Handle new JSON structure format
            if (jsonObject.has("links") && jsonObject.get("links").isJsonArray) {
                val links = jsonObject.getAsJsonArray("links")
                links.forEach { link ->
                    if (link.isJsonObject) {
                        val linkObj = link.asJsonObject
                        val url = linkObj.get("url")?.asString ?: ""
                        val name = linkObj.get("name")?.asString ?: "GDMirrorbot Link"
                        
                        if (url.isNotEmpty()) {
                            loadExtractor(url, name, subtitleCallback, callback)
                        }
                    }
                }
                return
            }
            
            // Handle traditional format
            val siteUrls = jsonObject["siteUrls"]?.takeIf { it.isJsonObject }?.asJsonObject
            val mresult = if (jsonObject.has("mresult") && jsonObject["mresult"].isJsonPrimitive) {
                try {
                    val encoded = jsonObject["mresult"].asString
                    JsonParser.parseString(encodedDecode(encoded)).asJsonObject
                } catch (e: Exception) {
                    jsonObject["mresult"]?.takeIf { it.isJsonObject }?.asJsonObject
                }
            } else {
                jsonObject["mresult"]?.takeIf { it.isJsonObject }?.asJsonObject
            }
            
            val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.takeIf { it.isJsonObject }?.asJsonObject
            
            if (siteUrls == null || siteFriendlyNames == null || mresult == null) {
                Log.e("Error:", "Missing required JSON fields in GDMirrorbot response")
                return
            }
            
            val commonKeys = siteUrls.keySet().intersect(mresult.asJsonObject.keySet())
            commonKeys.forEach { key ->
                val siteName = siteFriendlyNames[key]?.asString
                if (siteName == null) {
                    Log.e("Error:", "Skipping key: $key because siteName is null")
                    return@forEach
                }
                val siteUrl = siteUrls[key]?.asString
                val resultUrl = mresult.asJsonObject[key]?.asString
                if (siteUrl == null || resultUrl == null) {
                    Log.e("Error:", "Skipping key: $key because siteUrl or resultUrl is null")
                    return@forEach
                }
                val href = siteUrl + resultUrl
                loadExtractor(href, "GDMirrorbot: $siteName", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("GDMirrorbot", "Error: ${e.message}")
        }
    }
    
    private fun encodedDecode(encoded: String): String {
        return try {
            com.lagradost.cloudstream3.base64Decode(encoded)
        } catch (e: Exception) {
            encoded // Return as is if decode fails
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}

// New extractor for updated MultiMovies site
class MultiMoviesNewEmbed : ExtractorApi() {
    override var name = "MultiMoviesEmbed"
    override var mainUrl = "https://multimovies.digital"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("MultiMovies", "Processing URL: $url")
            val document = app.get(url, referer = referer).document
            
            // Try to find iframe sources
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty() && !src.contains("youtube")) {
                    try {
                        val iframeDoc = app.get(src, referer = url).document
                        
                        // Look for packed scripts in the iframe
                        val packedScript = iframeDoc.select("script").find { 
                            it.data().contains("eval(function(p,a,c,k,e,d)") 
                        }?.data()
                        
                        if (!packedScript.isNullOrEmpty()) {
                            val unpacked = JsUnpacker(packedScript).unpack()
                            
                            // Try different patterns to find m3u8 URLs
                            val m3u8Url = listOf(
                                Regex("file:[ \"']*([^\"']+\\.m3u8[^\"']*)").find(unpacked ?: ""),
                                Regex("src:[ \"']*([^\"']+\\.m3u8[^\"']*)").find(unpacked ?: ""),
                                Regex("source:[ \"']*([^\"']+\\.m3u8[^\"']*)").find(unpacked ?: "")
                            ).firstNotNullOfOrNull { it?.groupValues?.get(1) }
                            
                            if (!m3u8Url.isNullOrEmpty()) {
                                callback.invoke(
                                    ExtractorLink(
                                        this.name,
                                        "${this.name} Player",
                                        m3u8Url,
                                        src,
                                        Qualities.P1080.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                            }
                        }
                        
                        // Check for video elements in iframe
                        val videoSrc = iframeDoc.select("video source").attr("src")
                        if (videoSrc.isNotEmpty()) {
                            callback.invoke(
                                ExtractorLink(
                                    this.name,
                                    "${this.name} Direct",
                                    videoSrc,
                                    src,
                                    Qualities.P1080.value,
                                    type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("MultiMovies", "Error processing iframe: ${e.message}")
                    }
                }
            }
            
            // Check for scripts containing player data
            document.select("script").forEach { script ->
                val data = script.data()
                if (data.contains("player") || data.contains("file")) {
                    val m3u8Url = Regex("file:[ \"']*([^\"']+\\.m3u8[^\"']*)").find(data)?.groupValues?.get(1)
                    if (!m3u8Url.isNullOrEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                "${this.name} Script",
                                m3u8Url,
                                url,
                                Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
            }
            
            // Check for data-embed-url attributes
            document.select("[data-embed-url]").forEach { element ->
                val embedUrl = element.attr("data-embed-url")
                if (embedUrl.isNotEmpty()) {
                    loadExtractor(embedUrl, "$name Embed", subtitleCallback, callback)
                }
            }
            
            // Handle modern type players with class selectors
            document.select(".streamium-player, .player-embed, .video-embed").forEach { player ->
                val dataUrl = player.attr("data-src") ?: player.attr("data-url") ?: player.attr("data-embed")
                if (!dataUrl.isNullOrEmpty()) {
                    loadExtractor(dataUrl, "$name Player", subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("MultiMovies", "Error in new extractor: ${e.message}")
        }
    }
}

class MultimoviesVidstack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
            val hash = url.substringAfterLast("#")
            val encoded = app.get("$mainUrl/api/v1/video?id=$hash", headers = headers).text.trim()
            
            if (encoded.isEmpty()) {
                Log.e("MultimoviesVidstack", "Empty response from API")
                return emptyList()
            }
            
            val decryptedText = try {
                AesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
            } catch (e: Exception) {
                Log.e("MultimoviesVidstack", "Decryption error: ${e.message}")
                return emptyList()
            }
            
            val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: Regex("\"file\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/", "/")
                
            return if (!m3u8.isNullOrEmpty()) {
                listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                Log.e("MultimoviesVidstack", "No M3U8 URL found in response")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("MultimoviesVidstack", "Error: ${e.message}")
            return emptyList()
        }
    }
}

object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"

    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://movierulz2025.bar"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val iframeSrc = document.selectFirst("iframe")?.attr("src") ?: ""
            
            if (iframeSrc.isEmpty()) {
                Log.e("FilemoonV2", "No iframe found")
                return
            }
            
            val iframeDoc = app.get(
                iframeSrc, 
                headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "sec-fetch-dest" to "iframe"
                )
            ).document
            
            val packedScript = iframeDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            
            if (packedScript == null) {
                Log.e("FilemoonV2", "No packed script found")
                return
            }
            
            val unpacked = JsUnpacker(packedScript).unpack()
            val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(unpacked ?: "")?.groupValues?.get(1)
                ?: Regex("file:\"(.*?)\"").find(unpacked ?: "")?.groupValues?.get(1)
            
            if (m3u8 == null) {
                Log.e("FilemoonV2", "No m3u8 URL found in unpacked script")
                return
            }
            
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    url,
                    Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
        } catch (e: Exception) {
            Log.e("FilemoonV2", "Error: ${e.message}")
        }
    }
}

class Streamcasthub : ExtractorApi() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://multimovies.streamcasthub.store"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfterLast("/#")
            val m3u8 = "https://ss1.rackcloudservice.cyou/ic/$id/master.txt"
            
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    url,
                    Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
        } catch (e: Exception) {
            Log.e("Streamcasthub", "Error: ${e.message}")
        }
    }
}

class Strwishcom : StreamWishExtractor() {
    override val name = "Strwish"
    override val mainUrl = "https://strwish.com"
    override val requiresReferer = true
}

open class VidhideExtractor : ExtractorApi() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(
                url, referer = referer ?: "$mainUrl/", interceptor = WebViewResolver(
                    Regex("""master\.m3u8""")
                )
            )
            
            val sources = mutableListOf<ExtractorLink>()
            if (response.url.contains("m3u8")) {
                sources.add(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = response.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return sources
        } catch (e: Exception) {
            Log.e("VidhideExtractor", "Error: ${e.message}")
            return null
        }
    }
}
