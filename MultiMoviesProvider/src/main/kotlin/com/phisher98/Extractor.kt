package com.phisher98

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
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
    // Base URL might change
    override var mainUrl = "https://gdmirrorbot.nl" // Example, might need adjustment
    override val requiresReferer = true

    // Function to get base URL (scheme + host)
    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Get the actual host, as the initial URL might be different
            val initialResponse = app.get(url, referer = referer) // Follow redirects if any
            val effectiveUrl = initialResponse.url
            val host = getBaseUrl(effectiveUrl)
            val embedId = effectiveUrl.substringAfterLast("/")

            Log.d(name, "Fetching embed helper for ID: $embedId from host: $host")

            val data = mapOf("sid" to embedId)
            // Use the effective URL as referer for the POST request
            val jsonString = app.post("$host/embedhelper.php", data = data, referer = effectiveUrl).text

            val jsonElement: JsonElement = JsonParser.parseString(jsonString)
            if (!jsonElement.isJsonObject) {
                Log.e(name, "Unexpected JSON format from embedhelper.php for $url: Response is not a JSON object")
                return
            }
            val jsonObject = jsonElement.asJsonObject

            val siteUrls = jsonObject["siteUrls"]?.takeIf { it.isJsonObject }?.asJsonObject
            val mresultEncoded = jsonObject["mresult"]?.takeIf { it.isJsonPrimitive }?.asString
            val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.takeIf { it.isJsonObject }?.asJsonObject

            // Decode mresult if it exists and is a string
            val mresult = mresultEncoded?.let {
                try {
                    val decodedString = base64Decode(it) // Decode from Base64
                    JsonParser.parseString(decodedString).asJsonObject // Convert to JSON object
                } catch (e: Exception) {
                    Log.e(name, "Failed to decode or parse mresult for $url: ${e.message}")
                    null
                }
            }

            if (siteUrls == null || siteFriendlyNames == null || mresult == null) {
                Log.e(name, "Missing required fields (siteUrls, siteFriendlyNames, or mresult) in JSON response for $url")
                return
            }

            val commonKeys = siteUrls.keySet().intersect(mresult.keySet())
            Log.d(name, "Found ${commonKeys.size} common keys for $url")

            commonKeys.forEach { key ->
                try {
                    val siteName = siteFriendlyNames[key]?.asString
                    val siteUrl = siteUrls[key]?.asString
                    val resultUrl = mresult[key]?.asString

                    if (siteName == null || siteUrl == null || resultUrl == null) {
                        Log.w(name, "Skipping key '$key' due to missing siteName, siteUrl, or resultUrl for $url")
                        return@forEach // Continue to next key
                    }

                    val finalLink = siteUrl + resultUrl
                    Log.d(name, "Loading extractor for key '$key' ($siteName): $finalLink")
                    // Pass the original referer down to the next extractor
                    loadExtractor(finalLink, referer, subtitleCallback, callback)
                } catch (e: Exception) {
                     Log.e(name, "Error processing key '$key' for $url: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting GDMirrorbot links for $url: ${e.message}")
        }
    }
}


class MultimoviesVidstack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val headers= mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash=url.substringAfterLast("#")
        val encoded= app.get("$mainUrl/api/v1/video?id=$hash",headers=headers).text.trim()
        val decryptedText = AesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
        val m3u8=Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/") ?:""
        return listOf(
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


// Update FilemoonV2 extractor similar to the one in Movierulzhd
class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    // mainUrl is often dynamic, avoid hardcoding if possible or update frequently
    override var mainUrl = "https://filemoon.sx" // Example, might need adjustment
    override val requiresReferer = true

    // Updated Filemoon extractor logic (common pattern)
    private val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?""")
    private val sourceRegex = Regex("""sources:\[\{file:"(.*?)"""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Sometimes the initial URL is the direct Filemoon link
            val directUrl = if (URI(url).host.contains("filemoon")) url else {
                // Otherwise, try to extract iframe src
                app.get(url, referer = referer).document.selectFirst("iframe[src*=filemoon]")?.attr("abs:src")
            }

            if (directUrl == null) {
                Log.e(name, "Could not find Filemoon iframe/link for URL: $url")
                return
            }

            val filemoonPage = app.get(directUrl, referer = referer ?: url).document
            val packed = packedRegex.find(filemoonPage.html())?.value
            val unpacked = JsUnpacker(packed).unpack()

            if (unpacked == null) {
                Log.e(name, "Failed to unpack JS for URL: $directUrl")
                return
            }

            val m3u8 = sourceRegex.find(unpacked)?.groupValues?.getOrNull(1)
            if (m3u8 != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        m3u8,
                        directUrl, // Use filemoon URL as referer for the stream
                        Qualities.Unknown.value, // Quality detection from m3u8 is preferred
                        type = ExtractorLinkType.M3U8,
                    )
                )
            } else {
                Log.e(name, "Could not find m3u8 source in unpacked JS for URL: $directUrl")
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting Filemoon link for $url: ${e.message}")
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
        val id=url.substringAfterLast("/#")
        val m3u8= "https://ss1.rackcloudservice.cyou/ic/$id/master.txt"
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
        val response = app.get(
            url, referer = referer ?: "$mainUrl/", interceptor = WebViewResolver(
                Regex("""master\.m3u8""")
            )
        )
        val sources = mutableListOf<ExtractorLink>()
        if (response.url.contains("m3u8"))
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
        return sources
    }
}
