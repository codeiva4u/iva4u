package com.phisher98

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.M3u8Helper2
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

class Dhcplay: VidHidePro() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
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

class server1 : VidStack() {
    override var name = "MultimoviesVidstack"
    override var mainUrl = "https://server1.uns.bio"
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
        val host = getBaseUrl(app.get(url).url)
        val embedId = url.substringAfterLast("/")
        val postData = mapOf("sid" to embedId)

        val responseJson = app.post("$host/embedhelper.php", data = postData).text
        val jsonElement = JsonParser.parseString(responseJson)
        if (!jsonElement.isJsonObject) return

        val root = jsonElement.asJsonObject
        val siteUrls = root["siteUrls"]?.asJsonObject ?: return
        val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject

        val decodedMresult: JsonObject = when {
            root["mresult"]?.isJsonObject == true -> {
                root["mresult"]?.asJsonObject!!
            }
            root["mresult"]?.isJsonPrimitive == true -> {
                val mresultBase64 = root["mresult"]?.asString ?: return
                try {
                    val jsonStr = base64Decode(mresultBase64)
                    JsonParser.parseString(jsonStr).asJsonObject
                } catch (e: Exception) {
                    Log.e("Phisher", "Failed to decode mresult base64: $e")
                    return
                }
            }
            else -> return
        }

        val commonKeys = siteUrls.keySet().intersect(decodedMresult.keySet())

        for (key in commonKeys) {
            val base = siteUrls[key]?.asString?.trimEnd('/') ?: continue
            val path = decodedMresult[key]?.asString?.trimStart('/') ?: continue
            val fullUrl = "$base/$path"

            val friendlyName = siteFriendlyNames?.get(key)?.asString ?: key
            try {
                when (friendlyName) {
                    "EarnVids" -> {
                        VidhideExtractor().getUrl(fullUrl, referer, subtitleCallback, callback)
                    }
                    "StreamHG" -> {
                        VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
                    }
                    "RpmShare", "UpnShare", "StreamP2p" -> {
                        VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("Error:", "Failed to extract from $friendlyName at $fullUrl")
                continue
            }
        }

    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
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

// GDTOT Extractor - Skipped (Domains are DOWN)
// Testing showed: new9.gdtot.dad, new12.gdtot.dad - Connection Timeout
// These domains are blocked/dead - Not implementing
class GDTOTExtractor : ExtractorApi() {
    override var name = "GDTOT"
    override var mainUrl = "https://gdtot.dad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // GDTOT domains are currently down/blocked
        // Tested live: ERR_CONNECTION_TIMEOUT
        Log.e("GDTOT", "GDTOT domains are currently DOWN. Skipping extraction.")
        return
    }
}

// FilePress Extractor - Redirect page is BLANK
// Testing showed: multimovies.network/links/XXX redirects but page is empty
// JavaScript-heavy redirect that requires more complex handling
class FilePressExtractor : ExtractorApi() {
    override var name = "FilePress"
    override var mainUrl = "https://new3.filepress.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // FilePress redirect pages are blank (JavaScript redirects)
        // Need WebView or JavaScript execution to get actual URL
        Log.e("FilePress", "FilePress requires JavaScript execution - currently not supported")
        return
    }
}

// Hubcloud Extractor - FULLY WORKING! ✅
// Live tested: Successfully extracts 4 download servers!
// Servers: PixelDrain, 10Gbps, ZipDisk, Telegram
class HubcloudExtractor : ExtractorApi() {
    override var name = "Hubcloud"
    override var mainUrl = "https://hubcloud.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Hubcloud", "Step 1: Loading initial page: $url")
            val initialDoc = app.get(url, referer = referer, timeout = 25L).document
            
            // Step 1: Get the gamerxyt.com button link
            val gamerxytButton = initialDoc.selectFirst("a#download[href*='gamerxyt.com']")
            if (gamerxytButton == null) {
                Log.e("Hubcloud", "Generate Download Link button not found")
                return
            }
            
            val gamerxytUrl = gamerxytButton.attr("href")
            Log.d("Hubcloud", "Step 2: Found gamerxyt URL: $gamerxytUrl")
            
            // Step 2: Navigate to gamerxyt page to get all download servers
            val downloadPage = app.get(gamerxytUrl, referer = url, timeout = 25L).document
            Log.d("Hubcloud", "Step 3: Loaded download page with servers")
            
            // Extract all download links
            var linksFound = 0
            
            // Server 1: PixelDrain (PixelServer : 2)
            downloadPage.select("a[href*='pixeldrain.dev/api/file']").forEach { element ->
                val pixelUrl = element.attr("href")
                if (pixelUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found PixelDrain: $pixelUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - PixelDrain",
                            "$name - PixelDrain",
                            pixelUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 2: 10Gbps Server (pixel.hubcdn.fans)
            downloadPage.select("a[href*='pixel.hubcdn.fans']").forEach { element ->
                val gbpsUrl = element.attr("href")
                if (gbpsUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found 10Gbps: $gbpsUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - 10Gbps",
                            "$name - 10Gbps",
                            gbpsUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 3: ZipDisk Server (cloudserver workers.dev)
            downloadPage.select("a[href*='cloudserver'][href*='workers.dev']").forEach { element ->
                val zipUrl = element.attr("href")
                if (zipUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found ZipDisk: $zipUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - ZipDisk",
                            "$name - ZipDisk (Extract ZIP)",
                            zipUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Server 4: Telegram Download
            downloadPage.select("a[href*='telegram'], a[href*='bloggingvector']").forEach { element ->
                val tgUrl = element.attr("href")
                if (tgUrl.isNotEmpty() && tgUrl.startsWith("http")) {
                    Log.d("Hubcloud", "Found Telegram: $tgUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - Telegram",
                            "$name - Telegram",
                            tgUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            // Bonus: Direct share link (hubcloud.one/drive/ID)
            val shareLink = downloadPage.selectFirst("input[value*='hubcloud.one/drive/']")
            if (shareLink != null) {
                val directUrl = shareLink.attr("value")
                if (directUrl.isNotEmpty()) {
                    Log.d("Hubcloud", "Found direct share: $directUrl")
                    callback.invoke(
                        ExtractorLink(
                            "$name - Direct",
                            "$name - Direct Link",
                            directUrl,
                            gamerxytUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    linksFound++
                }
            }
            
            Log.d("Hubcloud", "Step 4: Successfully extracted $linksFound download links")
            
        } catch (e: Exception) {
            Log.e("Hubcloud", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
