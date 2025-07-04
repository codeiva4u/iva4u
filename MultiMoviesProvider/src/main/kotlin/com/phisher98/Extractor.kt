package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// Base class for StreamWish-like hosts
abstract class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val script = response.document.selectFirst("script:containsData(sources)")?.data()
            ?: return
        val sources =
            Regex("""sources:\s*(\[.*?\])""").find(script)?.groupValues?.getOrNull(1) ?: return
        try {
            val links =
                parseJson<List<ExtractorLink>>(sources.replace(Regex("file")) { "url" })
            links.forEach {
                callback.invoke(it)
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to parse sources: ${e.message}")
        }
    }
}

// Extractor for StreamHG
class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Url = app.get(url, interceptor = WebViewResolver(Regex("master\\.m3u8|master\\.txt"))).url
        generateM3u8(
            this.name,
            m3u8Url,
            url,
        ).forEach(callback)
    }
}

// Aggregator for GDMirrorbot
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
        coroutineScope {
            val document = app.get(url, referer = referer).document
            document.select("li.server-item[data-link]").map { server ->
                async {
                    val link = server.attr("data-link")
                    val serverName = server.select(".server-name").text()
                    try {
                        when {
                            serverName.contains("StreamHG", true) || link.contains("multimoviesshg") -> {
                                StreamHG().getUrl(link, url, subtitleCallback, callback)
                            }
                            serverName.contains("RpmShare", true) ||
                                    serverName.contains("UpnShare", true) ||
                                    serverName.contains("StreamP2p", true) ||
                                    link.contains("rpmhub") ||
                                    link.contains("uns.bio") ||
                                    link.contains("p2pplay") -> {
                                VidStack().getUrl(link, url, subtitleCallback, callback)
                            }
                            serverName.contains("EarnVids", true) || link.contains("smoothpre") -> {
                                VidhideExtractor().getUrl(link, url)?.forEach(callback)
                            }
                            else -> {
                                loadExtractor(link, url, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Failed to load extractor for $serverName ($link): ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }
}

// Extractor for VidStack and its variants
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

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }
}

// Helper for AES decryption
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

// Generic Vidhide extractor
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
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        return sources
    }
}
