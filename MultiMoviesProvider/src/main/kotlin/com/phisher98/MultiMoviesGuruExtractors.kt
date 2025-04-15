package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.gson.JsonParser
import com.lagradost.api.Log

/**
 * multimovies.guru Extractors
 * Based on analysis of the website and existing extractors
 */

// AES Helper for decryption
object MultiMoviesGuruAesHelper {
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

// MultiMoviesGuruVidstack Extractor for vidstack player
class MultiMoviesGuruVidstack : ExtractorApi() {
    override var name = "MultiMoviesGuruVidstack"
    override var mainUrl = "https://multimovies.guru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringAfterLast("#")
        val apiUrl = "$mainUrl/api/v1/video?id=$hash"
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        
        val encoded = app.get(apiUrl, headers = headers).text.trim()
        val decryptedText = MultiMoviesGuruAesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
        val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/","/") ?: ""
        
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
}

// MultiMoviesGuruStreamcasthub Extractor
class MultiMoviesGuruStreamcasthub : ExtractorApi() {
    override var name = "MultiMoviesGuruStreamcasthub"
    override var mainUrl = "https://multimovies.streamcasthub.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
    }
}

// MultiMoviesGuruFilemoon Extractor
class MultiMoviesGuruFilemoon : ExtractorApi() {
    override var name = "MultiMoviesGuruFilemoon"
    override var mainUrl = "https://multimovies.guru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = app.get(url).document.selectFirst("iframe")?.attr("src") ?: ""
        val res = app.get(href, headers = mapOf("Accept-Language" to "en-US,en;q=0.5", "sec-fetch-dest" to "iframe")).document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        val m3u8 = JsUnpacker(res).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                m3u8 ?: "",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}

// MultiMoviesGuruGDMirrorbot Extractor
class MultiMoviesGuruGDMirrorbot : ExtractorApi() {
    override var name = "MultiMoviesGuruGDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = getBaseUrl(app.get(url).url)
        val embed = url.substringAfterLast("/")
        val data = mapOf("sid" to embed)
        val jsonString = app.post("$host/embedhelper.php", data = data).toString()
        
        val jsonElement = JsonParser.parseString(jsonString)
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
        return java.net.URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}