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
        println("MovieRulzVidstack: Processing URL: $url")
        
        try {
            val hash = url.substringAfterLast("#")
            println("MovieRulzVidstack: Extracted hash: $hash")
            
            if (hash.isEmpty()) {
                println("MovieRulzVidstack: No hash found in URL: $url")
                return
            }
            
            val apiUrl = "$mainUrl/api/v1/video?id=$hash"
            println("MovieRulzVidstack: API URL: $apiUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to url
            )
            
            val response = app.get(apiUrl, headers = headers)
            val encoded = response.text.trim()
            println("MovieRulzVidstack: Response status: ${response.code}, Content length: ${encoded.length}")
            
            if (encoded.isEmpty()) {
                println("MovieRulzVidstack: Empty response from API")
                return
            }
            
            val decryptedText = MovieRulzAesHelper.decryptAES(encoded, "kiemtienmua911ca", "0123456789abcdef")
            println("MovieRulzVidstack: Decrypted text preview: ${decryptedText.take(100)}...")
            
            val m3u8Match = Regex("\"source\":\"(.*?)\"").find(decryptedText)
            val m3u8 = m3u8Match?.groupValues?.get(1)?.replace("\\/","/") ?: ""
            println("MovieRulzVidstack: Extracted m3u8 URL: $m3u8")
            
            if (m3u8.isNotEmpty()) {
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
                println("MovieRulzVidstack: Failed to extract m3u8 URL from decrypted text")
            }
        } catch (e: Exception) {
            println("MovieRulzVidstack: Error processing URL: ${e.message}")
        }
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
        println("MovieRulzFilemoon: Processing URL: $url")
        
        val doc = app.get(url).document
        val iframe = doc.selectFirst("iframe")
        val href = iframe?.attr("src") ?: ""
        
        println("MovieRulzFilemoon: Found iframe URL: $href")
        
        if (href.isEmpty()) {
            println("MovieRulzFilemoon: No iframe found on page: $url")
            return
        }
        
        // Enhanced headers to mimic browser request
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5", 
            "sec-fetch-dest" to "iframe", 
            "Referer" to url
        )
        
        val iframeResponse = app.get(href, headers = headers)
        val iframeDoc = iframeResponse.document
        val scriptData = iframeDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
        val res = scriptData?.data() ?: ""
        
        println("MovieRulzFilemoon: Script found: ${res.isNotEmpty()}")
        println("MovieRulzFilemoon: Script content preview: ${res.take(100)}...")
        
        // Check if res is not empty before unpacking
        val m3u8 = if (res.isNotEmpty()) {
            try {
                val unpacked = JsUnpacker(res).unpack()
                println("MovieRulzFilemoon: Unpacked content preview: ${unpacked?.take(100)}...")
                
                // Try multiple regex patterns to extract the m3u8 URL
                unpacked?.let { unPacked ->
                    // Try first pattern - most common in filemoon players
                    Regex("sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*['\"](.+?\\.m3u8.*?)['\"]\\s*\\}").find(unPacked)?.groupValues?.get(1)
                        // Try alternative patterns if first one fails
                        ?: Regex("file\\s*:\\s*['\"](.+?\\.m3u8.*?)['\"]\\s*").find(unPacked)?.groupValues?.get(1)
                        ?: Regex("source\\s*:\\s*['\"](.+?\\.m3u8.*?)['\"]\\s*").find(unPacked)?.groupValues?.get(1)
                        ?: Regex("['\"](.+?\\.m3u8.*?)['\"]\\s*").find(unPacked)?.groupValues?.get(1)
                        ?: ""
                } ?: ""
            } catch (e: Exception) {
                println("MovieRulzFilemoon: Error unpacking JS: ${e.message}")
                ""
            }
        } else ""
        
        println("MovieRulzFilemoon: Extracted m3u8 URL: $m3u8")
        
        if (m3u8.isNotEmpty()) {
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
            println("MovieRulzFilemoon: Failed to extract m3u8 URL from: $url")
        }
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