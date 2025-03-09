package com.Phisher98

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
        val headers= mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash=url.substringAfterLast("#")
        val encoded= app.get("$mainUrl/api/v1/video?id=$hash",headers=headers).text.trim()
        val decryptedText = AesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
        val m3u8=Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/") ?:""
        return listOf(
            ExtractorLink(
                this.name,
                this.name,
                m3u8,
                url,
                Qualities.Unknown.value,
                isM3u8 = true
            )
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
        val headers = mapOf(
            "Accept-Language" to "en-US,en;q=0.5",
            "sec-fetch-dest" to "iframe",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        )
        
        val document = app.get(url).document
        val iframe = document.selectFirst("iframe")?.attr("src") ?: ""
        
        // If iframe is empty, use the direct URL
        val targetUrl = if (iframe.isNotEmpty()) iframe else url
        
        val response = app.get(targetUrl, headers = headers)
        val scriptData = response.document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        
        val m3u8 = JsUnpacker(scriptData).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        
        if (!m3u8.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    targetUrl,
                    Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
        } else {
            // Fallback: If JsUnpacker doesn't find m3u8, search directly in HTML
            val directM3u8 = Regex("file:\\s*['\"](.+?\\.m3u8)['\"]|source\\s*src=['\"](.+?\\.m3u8)['\"]|file:\\s*['\"](.+?\\.mp4)['\"]|source\\s*src=['\"](.+?\\.mp4)['\"]")
                .find(response.text)?.groupValues?.firstOrNull { it.isNotEmpty() && (it.contains(".m3u8") || it.contains(".mp4")) }
            
            if (!directM3u8.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        directM3u8,
                        targetUrl,
                        Qualities.P1080.value,
                        type = if (directM3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }
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
        val sources = mutableListOf<ExtractorLink>()
        
        // Try using WebViewResolver first
        try {
            val response = app.get(
                url, referer = referer ?: "$mainUrl/", interceptor = WebViewResolver(
                    Regex("""master\.m3u8""")
                )
            )
            if (response.url.contains("m3u8")) {
                sources.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = response.url,
                        referer = referer ?: "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return sources
            }
        } catch (e: Exception) {
            // WebViewResolver failed, continue to fallback method
        }
        
        // Fallback: Try to extract directly from HTML
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to (referer ?: url)
            )
            
            val response = app.get(url, headers = headers)
            val document = response.document
            
            // Look for iframe
            val iframe = document.selectFirst("iframe")?.attr("src")
            val targetUrl = if (!iframe.isNullOrEmpty()) iframe else url
            
            // Get content from iframe if it exists
            val iframeContent = if (iframe != null) app.get(iframe, headers = headers).text else response.text
            
            // Try to find m3u8 or mp4 links in the content
            val directLink = Regex("file:\\\\s*['\"](.+?\\.m3u8)['\"]|source\\\\s*src=['\"](.+?\\.m3u8)['\"]|file:\\\\s*['\"](.+?\\.mp4)['\"]|source\\\\s*src=['\"](.+?\\.mp4)['\"]")
                .find(iframeContent)?.groupValues?.firstOrNull { it.isNotEmpty() && (it.contains(".m3u8") || it.contains(".mp4")) }
            
            if (!directLink.isNullOrEmpty()) {
                sources.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = directLink,
                        referer = targetUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = directLink.contains(".m3u8")
                    )
                )
                return sources
            }
        } catch (e: Exception) {
            // Both methods failed
        }
        
        return if (sources.isEmpty()) null else sources
    }
}
