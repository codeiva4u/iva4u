package com.hikaritv


import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Base64
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.util.zip.Inflater

class Ghbrisk : Filesim() {
    override val name = "Streamwish"
    override val mainUrl = "https://ghbrisk.com"
}

class Swishsrv : Filesim() {
    override val name = "Streamwish"
    override val mainUrl = "https://swishsrv.com"
}


class Boosterx : Chillx() {
    override val name = "Boosterx"
    override val mainUrl = "https://boosterx.stream"
}


open class Chillx : ExtractorApi() {
    override val name = "Chillx"
    override val mainUrl = "https://chillx.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).toString()
        val encodedString =
            Regex("Encrypted\\s*=\\s*'(.*?)';").find(res)?.groupValues?.get(1) ?:""
        Log.d("Phisher",encodedString)
        val decoded = decodeEncryptedData(encodedString)
        val m3u8 =Regex("file:\\s*\"(.*?)\"").find(decoded ?:"")?.groupValues?.get(1) ?:""
        val header =mapOf(
                "accept" to "*/*",
                "accept-language" to "en-US,en;q=0.5",
                "Origin" to mainUrl,
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "user-agent" to USER_AGENT,)
        callback.invoke(
            ExtractorLink(
                name,
                name,
                m3u8,
                mainUrl,
                Qualities.P1080.value,
                INFER_TYPE,
                headers = header
            )
        )

        val subtitles = extractSrtSubtitles(decoded ?:"")
        subtitles.forEachIndexed { _, (language, url) ->
            subtitleCallback.invoke(
                SubtitleFile(
                    language,
                    url
                )
            )
        }
    }

    private fun extractSrtSubtitles(subtitle: String): List<Pair<String, String>> {
        val regex = """\[([^]]+)](https?://[^\s,]+\.srt)""".toRegex()

        return regex.findAll(subtitle).map { match ->
            val (language, url) = match.destructured
            language.trim() to url.trim()
        }.toList()
    }


    private fun decodeEncryptedData(encryptedString: String?): String? {
        if (encryptedString == null) return null

        return try {
            // Base64 decode using android.util.Base64
            val decodedBytes = Base64.decode(encryptedString, Base64.DEFAULT)

            // Reverse binary and decode characters
            val decodedCharacters = decodedBytes.map { byte ->
                val binaryRepresentation = byte.toUByte().toString(2).padStart(8, '0')
                val reversedBinary = binaryRepresentation.reversed()
                reversedBinary.toInt(2).toByte()
            }
            Log.d("Phisher",decodedCharacters.toString())

            val byteArray = ByteArray(decodedCharacters.size) { decodedCharacters[it] }
            val decompressedData = Inflater().run {
                setInput(byteArray)
                val output = ByteArray(1024 * 4)
                val decompressedSize = inflate(output)
                output.copyOf(decompressedSize).toString(Charsets.UTF_8)
            }
            val specialToAlphabetMap = mapOf(
                '!' to 'a', '@' to 'b', '#' to 'c', '$' to 'd', '%' to 'e',
                '^' to 'f', '&' to 'g', '*' to 'h', '(' to 'i', ')' to 'j'
            )
            val processedData = decompressedData.map { char ->
                specialToAlphabetMap[char] ?: char
            }.joinToString("")
            val finalDecodedData = Base64.decode(processedData, Base64.DEFAULT).toString(Charsets.UTF_8)
            Log.d("Phisher",finalDecodedData)
            finalDecodedData
        } catch (e: Exception) {
            println("Error decoding string: ${e.message}")
            null
        }
    }

}


class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href=app.get(url).document.selectFirst("iframe")?.attr("src") ?:""
        Log.d("Phisher",href)
        val res= app.get(href, headers = mapOf("Accept-Language" to "en-US,en;q=0.5","sec-fetch-dest" to "iframe")).document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        val m3u8= JsUnpacker(res).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        Log.d("Phisher",m3u8.toString())

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                m3u8 ?:"",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}