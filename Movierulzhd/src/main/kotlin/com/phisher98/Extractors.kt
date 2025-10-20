package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ======================== VIDEO HOSTING EXTRACTORS ========================

// GDMirrorBot Extractor
open class GDMirrorbot : ExtractorApi() {
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
            val (sid, host) = if (!url.contains("key=")) {
                Pair(url.substringAfterLast("embed/"), getBaseUrl(app.get(url).url))
            } else {
                var pageText = app.get(url).text
                val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
                val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
                val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"
                val baseUrl = Regex("""let\s+baseUrl\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
                val hostUrl = baseUrl?.let { getBaseUrl(it) }

                if (finalId != null && myKey != null) {
                    val apiUrl = "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                    pageText = app.get(apiUrl).text
                }

                val jsonElement = JsonParser.parseString(pageText)
                if (!jsonElement.isJsonObject) return
                val jsonObject = jsonElement.asJsonObject

                val embedId = url.substringAfterLast("/")
                val sidValue = jsonObject["data"]?.asJsonArray
                    ?.takeIf { it.size() > 0 }
                    ?.get(0)?.asJsonObject
                    ?.get("fileslug")?.asString
                    ?.takeIf { it.isNotBlank() } ?: embedId

                Pair(sidValue, hostUrl)
            }

            val postData = mapOf("sid" to sid)
            val responseText = app.post("$host/embedhelper.php", data = postData).text

            val rootElement = JsonParser.parseString(responseText)
            if (!rootElement.isJsonObject) return
            val root = rootElement.asJsonObject

            val siteUrls = root["siteUrls"]?.asJsonObject ?: return
            val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject

            val decodedMresult = when {
                root["mresult"]?.isJsonObject == true -> root["mresult"]!!.asJsonObject
                root["mresult"]?.isJsonPrimitive == true -> try {
                    base64Decode(root["mresult"]!!.asString)
                        .let { JsonParser.parseString(it).asJsonObject }
                } catch (e: Exception) {
                    Log.e("GDMirrorbot", "Failed to decode mresult: $e")
                    return
                }
                else -> return
            }

            siteUrls.keySet().intersect(decodedMresult.keySet()).forEach { key ->
                val base = siteUrls[key]?.asString?.trimEnd('/') ?: return@forEach
                val path = decodedMresult[key]?.asString?.trimStart('/') ?: return@forEach
                val fullUrl = "$base/$path"
                val friendlyName = siteFriendlyNames?.get(key)?.asString ?: key

                try {
                    Log.d("GDMirrorbot", "$friendlyName: $fullUrl")
                    when (friendlyName) {
                        "StreamHG", "EarnVids" -> VidhideExtractor().getUrl(fullUrl, referer, subtitleCallback, callback)
                        "RpmShare", "UpnShare", "StreamP2p" -> VidstackExtractor().getUrl(fullUrl, referer, subtitleCallback, callback)
                        else -> Log.d("GDMirrorbot", "Unknown source: $friendlyName")
                    }
                } catch (e: Exception) {
                    Log.e("GDMirrorbot", "Failed to extract from $friendlyName: $e")
                }
            }
        } catch (e: Exception) {
            Log.e("GDMirrorbot", "Error: ${e.message}")
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

// VidStack Extractor
open class VidstackExtractor : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
            val hash = url.substringAfterLast("#").substringAfter("/")
            val baseurl = getBaseUrl(url)

            val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

            val key = "kiemtienmua911ca"
            val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

            val decryptedText = ivList.firstNotNullOfOrNull { iv ->
                try {
                    AesHelper.decryptAES(encoded, key, iv)
                } catch (e: Exception) {
                    null
                }
            } ?: throw Exception("Failed to decrypt with all IVs")

            val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: ""

            if (m3u8.isNotEmpty()) {
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
            }
        } catch (e: Exception) {
            Log.e("VidstackExtractor", "Error: ${e.message}")
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            Log.e("VidstackExtractor", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }
}

// VidHide Extractor
open class VidhideExtractor : ExtractorApi() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url, referer = referer ?: "$mainUrl/", interceptor = WebViewResolver(
                    Regex("""master\.m3u8""")
                )
            )
            
            if (response.url.contains("m3u8")) {
                callback.invoke(
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
        } catch (e: Exception) {
            Log.e("VidhideExtractor", "Error: ${e.message}")
        }
    }
}

// StreamTape Extractor
open class StreamTapeExtractor : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val scriptData = document.selectFirst("script:containsData(getElementById)")?.data()
            
            val videoUrlRegex = Regex("""getElementById\('robotlink'\)\.innerHTML = '(.+?)' \+ \('(.+?)'\)""")
            val match = videoUrlRegex.find(scriptData ?: "")
            
            if (match != null) {
                val part1 = match.groupValues[1]
                val part2 = match.groupValues[2].substring(3)
                val videoUrl = "https:$part1$part2"
                
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("StreamTapeExtractor", "Error: ${e.message}")
        }
    }
}

// Filemoon Extractor
open class FilemoonExtractor : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, interceptor = WebViewResolver(Regex("""master\.m3u8""")))
            
            if (response.url.contains("m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = response.url,
                    referer = url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Error: ${e.message}")
        }
    }
}

// Doodstream Extractor
open class DoodstreamExtractor : ExtractorApi() {
    override val name = "Doodstream"
    override val mainUrl = "https://dood.li"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val passRegex = Regex("""/pass_md5/[\\w-]+""")
            val passPath = passRegex.find(document.html())?.value ?: return
            
            val passUrl = "${getBaseUrl(url)}$passPath"
            val passResponse = app.get(passUrl, referer = url).text
            
            val videoUrl = "$passResponse${System.currentTimeMillis()}?token=${url.substringAfterLast("/")}"
            
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e("DoodstreamExtractor", "Error: ${e.message}")
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

// StreamWish Extractor
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
            val response = app.get(url, interceptor = WebViewResolver(Regex("""master\.m3u8""")))
            
            if (response.url.contains("m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = response.url,
                    referer = url
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("StreamWishExtractor", "Error: ${e.message}")
        }
    }
}

// Multi-host Extractor (handles multiple video hosting domains)
open class MultiHostExtractor : ExtractorApi() {
    override val name = "MultiHost"
    override val mainUrl = ""
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(Regex("""\.(m3u8|mp4|mkv)"""))
            )
            
            val finalUrl = response.url
            when {
                finalUrl.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = "MultiHost",
                        streamUrl = finalUrl,
                        referer = url
                    ).forEach(callback)
                }
                finalUrl.contains(".mp4") || finalUrl.contains(".mkv") -> {
                    callback.invoke(
                        newExtractorLink(
                            "MultiHost",
                            "MultiHost",
                            finalUrl
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MultiHostExtractor", "Error: ${e.message}")
        }
    }
}

// ======================== HELPER CLASSES ========================

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
