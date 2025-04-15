package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 1movierulzhd.lol Extractors
 * Based on analysis of the website and existing extractors
 */

// AES Helper for decryption
object MovieRulzAesHelper {
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

// MovieRulzVidstack Extractor for vidstack player
class MovieRulzVidstack : ExtractorApi() {
    override var name = "MovieRulzVidstack"
    override var mainUrl = "https://1movierulzhd.lol"
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
        val decryptedText = MovieRulzAesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
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

// MovieRulzStreamcasthub Extractor
class MovieRulzStreamcasthub : ExtractorApi() {
    override var name = "MovieRulzStreamcasthub"
    override var mainUrl = "https://1movierulzhd.streamcasthub.xyz"
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

// MovieRulzFilemoon Extractor
class MovieRulzFilemoon : ExtractorApi() {
    override var name = "MovieRulzFilemoon"
    override var mainUrl = "https://1movierulzhd.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = app.get(url).document.selectFirst("iframe")?.attr("src") ?: ""
        // Added headers to mimic browser request
        val headers = mapOf(
            "Accept-Language" to "en-US,en;q=0.5", 
            "sec-fetch-dest" to "iframe",
            "Referer" to url // Often needed for iframe content
        )
        val res = app.get(href, headers = headers).document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        val m3u8 = JsUnpacker(res).unpack()?.let { unPacked ->
            // Corrected Regex escaping for Kotlin string
            Regex("sources:\\[\\{file:\"(.*?)\"")
                .find(unPacked)?.groupValues?.get(1) ?: ""
        } ?: ""
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                m3u8 ?: "",
                url,
                Qualities.P1080.value, // Assuming 1080p, adjust if needed
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}

// MovieRulzAkamaicdn Extractor
open class MovieRulzAkamaicdn : ExtractorApi() {
    override val name = "MovieRulzAkamaicdn"
    override val mainUrl = "https://akamaicdn.life"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("user-agent" to "okhttp/4.12.0")
        val res = app.get(url, referer = referer, headers = headers).document
        // Corrected selector to avoid invalid Kotlin escape sequence
        val mappers = res.selectFirst("script:containsData(sniff())")?.data()?.substringAfter("sniff(")
        val ids = mappers?.split(",")?.map { it.replace("\"", "") }
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "$mainUrl/m3u8/${ids?.get(1)}/${ids?.get(2)}/master.txt?s=1&cache=1",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                headers = headers
            )
        )
    }
}

// MovieRulzMocdn Extractor
class MovieRulzMocdn : MovieRulzAkamaicdn() {
    override val name = "MovieRulzMocdn"
    override val mainUrl = "https://mocdn.art" //Added missing mainUrl based on previous context
}