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
        Log.d("Phisher","I'm here")

        val host = getBaseUrl(app.get(url).url)
        val embed = url.substringAfterLast("/")
        val data = mapOf("sid" to embed)
        val jsonString = app.post("$host/embedhelper.php", data = data).toString()
        Log.d("Phisher",jsonString)

        val jsonElement: JsonElement = JsonParser.parseString(jsonString)
        if (!jsonElement.isJsonObject) {
            Log.e("Error:", "Unexpected JSON format: Response is not a JSON object")
            return
        }
        val jsonObject = jsonElement.asJsonObject
        val siteUrls = jsonObject["siteUrls"]?.takeIf { it.isJsonObject }?.asJsonObject
        val mresult = jsonObject["mresult"]?.takeIf { it.isJsonObject }?.asJsonObject
        val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.takeIf { it.isJsonObject }?.asJsonObject
        if (siteUrls == null || siteFriendlyNames == null || mresult == null) {
            return
        }
        val commonKeys = siteUrls.keySet().intersect(mresult.keySet())
        commonKeys.forEach { key ->
            val siteName = siteFriendlyNames[key]?.asString
            if (siteName == null) {
                Log.e("Error:", "Skipping key: $key because siteName is null")
                return@forEach
            }
            val siteUrl = siteUrls[key]?.asString
            val resultUrl = mresult[key]?.asString
            if (siteUrl == null || resultUrl == null) {
                Log.e("Error:", "Skipping key: $key because siteUrl or resultUrl is null")
                return@forEach
            }
            val href = siteUrl + resultUrl
            loadExtractor(href, subtitleCallback, callback)
        }

    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}


class MultimoviesVidstack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
            "Referer" to "https://multimovies.rpmhub.site/"
        )
        
        try {
            val hash = url.substringAfterLast("#")
            val response = app.get("$mainUrl/api/v1/video?id=$hash", headers = headers)
            
            if (response.code == 200) {
                val encoded = response.text.trim()
                val decryptedText = AesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
                
                // Try multiple patterns to extract the source
                val m3u8 = Regex(""""source":"(.*?)"""").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/")
                    ?: Regex("""file:"(.*?)"""").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/")
                    ?: Regex("""src:\s*"(.*?)"|src:\s*'(.*?)'""").find(decryptedText)?.let {
                        it.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } 
                            ?: it.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    }?.replace("\\/","/")
                    ?: Regex("""file:\s*"(.*?)"""").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/")
                    ?: ""
                
                Log.d("Phisher", "Extracted m3u8: $m3u8")
                
                if (m3u8.isNotEmpty()) {
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
        } catch (e: Exception) {
            Log.e("Phisher", "Error in MultimoviesVidstack: ${e.message}")
            e.printStackTrace()
        }
        
        return emptyList()
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
            Log.d("Phisher", "Processing FilemoonV2 URL: $url")
            val href = app.get(url).document.selectFirst("iframe")?.attr("src") ?: ""
            Log.d("Phisher", "Found iframe src: $href")
            
            if (href.isNotEmpty()) {
                val headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "sec-fetch-dest" to "iframe",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
                )
                
                val res = app.get(href, headers = headers).document
                    .selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
                
                Log.d("Phisher", "Unpacking JavaScript")
                val unpackedJs = JsUnpacker(res).unpack()
                
                if (unpackedJs != null) {
                    // Try multiple patterns to extract the source
                    val m3u8 = Regex("""sources:\[\{file:"(.*?)"""").find(unpackedJs)?.groupValues?.get(1)
                        ?: Regex("""src:\s*"(.*?)"""").find(unpackedJs)?.groupValues?.get(1)
                        ?: Regex("""file:\s*"(.*?)"""").find(unpackedJs)?.groupValues?.get(1)
                        ?: Regex("""file:'(.*?)'""").find(unpackedJs)?.groupValues?.get(1)
                    
                    Log.d("Phisher", "Extracted m3u8: $m3u8")
                    
                    if (!m3u8.isNullOrEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                this.name,
                                m3u8,
                                url,
                                Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8,
                                headers = headers
                            )
                        )
                    } else {
                        Log.e("Phisher", "Failed to extract m3u8 URL from unpacked JavaScript")
                    }
                } else {
                    Log.e("Phisher", "Failed to unpack JavaScript")
                }
            } else {
                Log.e("Phisher", "No iframe found in the page")
            }
        } catch (e: Exception) {
            Log.e("Phisher", "Error in FilemoonV2: ${e.message}")
            e.printStackTrace()
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
