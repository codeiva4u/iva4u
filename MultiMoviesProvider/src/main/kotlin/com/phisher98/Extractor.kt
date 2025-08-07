package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.AnyVidplay
import com.lagradost.cloudstream3.extractors.RowdyAvocadoKeys
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class Guccihide : Filesim() {
    override val name = "Guccihide"
    override var mainUrl = "https://files.im"
}

class Ahvsh : Filesim() {
    override val name = "Ahvsh"
    override var mainUrl = "https://ahvsh.com"
}

class Moviesm4u : Filesim() {
    override val mainUrl = "https://moviesm4u.com"
    override val name = "Moviesm4u"
}

class FileMoonIn : Filesim() {
    override val mainUrl = "https://filemoon.in"
    override val name = "FileMoon"
}

class StreamhideTo : Filesim() {
    override val mainUrl = "https://streamhide.com"
    override val name = "Streamhide"
}

class StreamhideCom : Filesim() {
    override var name: String = "Streamhide"
    override var mainUrl: String = "https://streamhide.com"
}

class Movhide : Filesim() {
    override var name: String = "Movhide"
    override var mainUrl: String = "https://movhide.pro"
}

class Ztreamhub : Filesim() {
    override val mainUrl: String = "https://ztreamhub.com" //Here 'cause works
    override val name = "Zstreamhub"
}
class FileMoon : Filesim() {
    override val mainUrl = "https://filemoon.to"
    override val name = "FileMoon"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.in"
    override val name = "FileMoonSx"
}

open class Filesim : ExtractorApi() {
    override val name = "Filesim"
    override val mainUrl = "https://files.im"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var response = app.get(url.replace("/download/", "/e/"), referer = referer)
        val iframe = response.document.selectFirst("iframe")
        if (iframe != null) {
            response = app.get(
                iframe.attr("src"), headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Sec-Fetch-Dest" to "iframe"
                ), referer = response.url
            )
        }

        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        val m3u8 =
            Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script)?.groupValues?.getOrNull(1)
        val links = generateM3u8(
            name,
            m3u8 ?: return,
            mainUrl
        )
        for (link in links) {
            callback(link)
        }
    }
}

class VidHidePro1 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
}

class Dhcplay: VidHidePro() {
    override var name = "DHC Play"
    override var mainUrl = "https://smoothpre.com"
}

class VidHidePro2 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
}

class VidHidePro3 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
}

class VidHidePro4 : VidHidePro() {
    override val mainUrl = "https://kinoger.be"
}

class VidHidePro5: VidHidePro() {
    override val mainUrl = "https://kinoger.be"
}

class VidHidePro6 : VidHidePro() {
    override val mainUrl = "https://smoothpre.com"
}

class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Dhtpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

open class VidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://filelions.live"
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
        val m3u8Matches = Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script)
        for (m3u8Match in m3u8Matches) {
            val links = generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            )
            for (link in links) {
                callback(link)
            }
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


@OptIn(ExperimentalEncodingApi::class)
open class VidSrcTo : ExtractorApi() {
    override val name = "VidSrcTo"
    override val mainUrl = "https://vidcloud.icu"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url)
            val doc = response.document
            
            // Try different selectors for media ID
            val mediaId = doc.selectFirst("ul.episodes li a")?.attr("data-id")
                ?: doc.selectFirst("[data-id]")?.attr("data-id")
                ?: doc.selectFirst("#playeroptionsul li")?.attr("data-post")
                ?: return
                
            // Get subtitles
            try {
                val subtitlesLink = "$mainUrl/ajax/embed/episode/$mediaId/subtitles"
                val subRes = app.get(subtitlesLink).parsedSafe<Array<VidsrctoSubtitles>>()
                subRes?.forEach {
                    if (it.kind.equals("captions")) subtitleCallback.invoke(SubtitleFile(it.label, it.file))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Get video sources
            try {
                val sourcesLink = "$mainUrl/ajax/embed/episode/$mediaId/sources?token=${vrfEncrypt(
                    RowdyAvocadoKeys.getKeys(), mediaId)}"
                val res = app.get(sourcesLink).parsedSafe<VidsrctoEpisodeSources>() ?: return
                if (res.status != 200) return
                
                res.result?.amap { source ->
                    try {
                        val embedResUrl = "$mainUrl/ajax/embed/source/${source.id}?token=${vrfEncrypt(RowdyAvocadoKeys.getKeys(), source.id)}"
                        val embedRes = app.get(embedResUrl).parsedSafe<VidsrctoEmbedSource>() ?: return@amap
                        val finalUrl = vrfDecrypt(RowdyAvocadoKeys.getKeys(), embedRes.result.encUrl)
                        if(finalUrl.equals(embedRes.result.encUrl)) return@amap
                        
                        when (source.title) {
                            "Server 1" -> {
                                try {
                                    AnyVidplay(finalUrl.substringBefore("/e/")).getUrl(finalUrl, referer, subtitleCallback, callback)
                                } catch (e: Exception) {
                                    logError(e)
                                    loadExtractor(finalUrl, referer, subtitleCallback, callback)
                                }
                            }
                            "Server 2" -> {
                                try {
                                    FileMoon().getUrl(finalUrl, referer, subtitleCallback, callback)
                                } catch (e: Exception) {
                                    logError(e)
                                    loadExtractor(finalUrl, referer, subtitleCallback, callback)
                                }
                            }
                            else -> loadExtractor(finalUrl, referer, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun vrfEncrypt(keys: RowdyAvocadoKeys.KeysData, input: String): String {
        var vrf = input
        keys.vidsrcto.sortedBy { it.sequence }.forEach { step ->
            when(step.method) {
                "exchange" -> vrf = exchange(vrf, step.keys?.get(0) ?: return@forEach, step.keys!!.get(1))
                "rc4" -> vrf = rc4Encryption(step.keys?.get(0) ?: return@forEach, vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.UrlSafe.encode(vrf.toByteArray())
                "else" -> {}
            }
        }
        // vrf = java.net.URLEncoder.encode(vrf, "UTF-8")
        return vrf
    }

    private fun vrfDecrypt(keys: RowdyAvocadoKeys.KeysData, input: String): String {
        var vrf = input
        keys.vidsrcto.sortedByDescending { it.sequence }.forEach { step ->
            when(step.method) {
                "exchange" -> vrf = exchange(vrf, step.keys?.get(1) ?: return@forEach, step.keys!!.get(0))
                "rc4" -> vrf = rc4Decryption(step.keys?.get(0) ?: return@forEach, vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.UrlSafe.decode(vrf).toString(Charsets.UTF_8)
                "else" -> {}
            }
        }
        return URLDecoder.decode(vrf, "utf-8")
    }

    private fun rc4Encryption(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        var output = cipher.doFinal(input.toByteArray())
        output = Base64.UrlSafe.encode(output).toByteArray()
        return output.toString(Charsets.UTF_8)
    }

    private fun rc4Decryption(key: String, input: String): String {
        var vrf = input.toByteArray()
        vrf = Base64.UrlSafe.decode(vrf)
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)

        return vrf.toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, key1: String, key2: String): String {
        return input.map { i ->
            val index = key1.indexOf(i)
            if (index != -1) {
                key2[index]
            } else {
                i
            }
        }.joinToString("")
    }

    data class VidsrctoEpisodeSources(
        @JsonProperty("status") val status: Int,
        @JsonProperty("result") val result: List<VidsrctoResult>?
    )

    data class VidsrctoResult(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String
    )

    data class VidsrctoEmbedSource(
        @JsonProperty("status") val status: Int,
        @JsonProperty("result") val result: VidsrctoUrl
    )

    data class VidsrctoSubtitles(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String
    )

    data class VidsrctoUrl(@JsonProperty("url") val encUrl: String)
}

class Luluvdo : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

class Lulust : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

class Movierulz : VidStack() {
    override var name = "Movierulz"
    override var mainUrl = "https://movierulz2025.bar"
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url,referer=mainUrl).document
        val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        INFER_TYPE
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    override val mainUrl = "https://cdnmovies.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers= mapOf("user-agent" to "okhttp/4.12.0")
        val res = app.get(url, referer = referer, headers = headers).document
        val sniffScript = res.selectFirst("script:containsData(sniff\\()")
            ?.data()
            ?.substringAfter("sniff(")
            ?.substringBefore(");") ?: return
        val ids = sniffScript.split(",").map { it.replace("\"", "").trim() }
        val m3u8 = "https://cdnmovies.net/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1&plt=${ids[16].substringBefore(" //")}"

        callback.invoke(
            newExtractorLink(
                name,
                name,
                m3u8,
                ExtractorLinkType.M3U8
            )
            {
                this.referer=url
                this.quality=Qualities.P1080.value
                this.headers=headers

            }
        )
    }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new6.gdflix.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        source: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = try {
            app.get(url)
                .document
                .selectFirst("meta[http-equiv=refresh]")
                ?.attr("content")
                ?.substringAfter("url=")
        } catch (e: Exception) {
            Log.e("Error", "Failed to fetch redirect: ${e.localizedMessage}")
            return
        } ?: url

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()

            when {
                text.contains("DIRECT DL",ignoreCase = true) -> {
                    val link = anchor.attr("href")
                    callback.invoke(
                        newExtractorLink("$source GDFlix[Direct]", "$source GDFlix[Direct] [$fileSize]", link) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("Index Links",ignoreCase = true) -> {
                    try {
                        val link = anchor.attr("href")
                        app.get("https://new6.gdflix.dad$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = "https://new6.gdflix.dad" + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val sourceurl = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("$source GDFlix[Index]", "$source GDFlix[Index] [$fileSize]", sourceurl) {
                                                this.quality = getIndexQuality(fileName)
                                            }
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

                text.contains("DRIVEBOT",ignoreCase = true) -> {
                    try {
                        val driveLink = anchor.attr("href")
                        val id = driveLink.substringAfter("id=").substringBefore("&")
                        val doId = driveLink.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://drivebot.cfd")

                        baseUrls.amap { baseUrl ->
                            val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                            val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                            if (indexbotResponse.isSuccessful) {
                                val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                val indexbotDoc = indexbotResponse.document

                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val requestBody = FormBody.Builder()
                                    .add("token", token)
                                    .build()

                                val headers = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$baseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = headers,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                callback.invoke(
                                    newExtractorLink("$source GDFlix[DriveBot]", "$source GDFlix[DriveBot] [$fileSize]", downloadLink) {
                                        this.referer = baseUrl
                                        this.quality = getIndexQuality(fileName)
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                text.contains("Instant DL",ignoreCase = true) -> {
                    try {
                        val instantLink = anchor.attr("href")
                        val link = app.get(instantLink, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        callback.invoke(
                            newExtractorLink("$source GDFlix[Instant Download]", "$source GDFlix[Instant Download] [$fileSize]", link) {
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }


                text.contains("GoFile",ignoreCase = true) -> {
                    try {
                        app.get(anchor.attr("href")).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Gofile().getUrl(link, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }

                text.contains("PixelDrain",ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$source GDFlix[Pixeldrain]",
                            "$source GDFlix[Pixeldrain] [$fileSize]",
                            anchor.attr("href"),
                        ) { this.quality = quality }
                    )
                }

                else -> {
                    Log.d("Error", "No Server matched")
                }
            }
        }

        // Cloudflare backup links
        try {
            val types = listOf("type=1", "type=2")
            types.map { type ->
                val sourceurl = app.get("${newUrl.replace("file", "wfile")}?$type")
                    .document.select("a.btn-success").attr("href")

                if (source?.isNotEmpty() == true) {
                    callback.invoke(
                        newExtractorLink("$source GDFlix[CF]", "$source GDFlix[CF] [$fileSize]", sourceurl) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("CF", e.toString())
        }
    }
}


class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        //val res = app.get(url).document
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val genAccountRes = app.post("$mainApi/accounts").text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token") ?: return

        val globalRes = app.get("$mainUrl/dist/js/global.js").text
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?wt=$wt",
            headers = mapOf(
                "Authorization" to "Bearer $token",
            )
        ).text

        val jsonResponse = JSONObject(response)
        val data = jsonResponse.getJSONObject("data")
        val children = data.getJSONObject("children")
        val oId = children.keys().next()
        val link = children.getJSONObject(oId).getString("link")
        val fileName = children.getJSONObject(oId).getString("name")
        val size = children.getJSONObject(oId).getLong("size")
        val formattedSize = if (size < 1024L * 1024 * 1024) {
            val sizeInMB = size.toDouble() / (1024 * 1024)
            "%.2f MB".format(sizeInMB)
        } else {
            val sizeInGB = size.toDouble() / (1024 * 1024 * 1024)
            "%.2f GB".format(sizeInGB)
        }

        callback.invoke(
            newExtractorLink(
                "Gofile",
                "Gofile [$formattedSize]",
                link,
            ) {
                this.quality = getQuality(fileName)
                this.headers = mapOf(
                    "Cookie" to "accountToken=$token"
                )
            }
        )
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}