package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Dhtpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dhtpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://peytonepre.com"
}

open class VidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://vidhidepro.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        // m3u8 urls could be prefixed by 'file:', 'hls2:' or 'hls4:', so we just match ':'
        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }

}

class Server1uns : VidStack() {
    override var name = "Vidstack"
    override var mainUrl = "https://server1.uns.bio"
    override var requiresReferer = true
}


open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    )
    {
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
        val subtitlePattern = Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"")
        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)

        subtitleSection?.let { section ->
            subtitlePattern.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
            mainUrl
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

class multimoviesshg : StreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://multimoviesshg.com"
}

open class StreamWishExtractor : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to "$mainUrl/",
            "Origin" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )

        val pageResponse = app.get(resolveEmbedUrl(url), referer = referer)

        val playerScriptData = when {
            !getPacked(pageResponse.text).isNullOrEmpty() -> getAndUnpack(pageResponse.text)
            pageResponse.document.select("script").any { it.html().contains("jwplayer(\"vplayer\").setup(") } ->
                pageResponse.document.select("script").firstOrNull {
                    it.html().contains("jwplayer(\"vplayer\").setup(")
                }?.html()
            else -> pageResponse.document.selectFirst("script:containsData(sources:)")?.data()
        }

        val directStreamUrl = playerScriptData?.let {
            Regex("""file:\s*"(.*?m3u8.*?)"""").find(it)?.groupValues?.getOrNull(1)
        }

        if (!directStreamUrl.isNullOrEmpty()) {
            M3u8Helper.generateM3u8(
                name,
                directStreamUrl,
                mainUrl,
                headers = headers
            ).forEach(callback)
        } else {
            val webViewM3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""txt|m3u8"""),
                additionalUrls = listOf(Regex("""txt|m3u8""")),
                useOkhttp = false,
                timeout = 15_000L
            )

            val interceptedStreamUrl = app.get(
                url,
                referer = referer,
                interceptor = webViewM3u8Resolver
            ).url

            if (interceptedStreamUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8(
                    name,
                    interceptedStreamUrl,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
            } else {
                Log.d("StreamwishExtractor", "No m3u8 found in fallback either.")
            }
        }
    }

    private fun resolveEmbedUrl(inputUrl: String): String {
        return if (inputUrl.contains("/f/")) {
            val videoId = inputUrl.substringAfter("/f/")
            "$mainUrl/$videoId"
        } else {
            inputUrl
        }
    }
}