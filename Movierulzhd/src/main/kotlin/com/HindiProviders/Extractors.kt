package com.Phisher98

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.util.Base64

class FMHD : Filesim() {
    override val name = "FMHD"
    override var mainUrl = "https://fmhd.bar/"
    override val requiresReferer = true
}

class VidSrcExtractorio : VidSrcExtractor() {
    override val mainUrl = "https://vidsrc.me"
}

class VidSrcExtractorcc : VidSrcExtractor() {
    override val mainUrl = "https://vidsrc.cc"
}

class Playonion : Filesim() {
    override val mainUrl = "https://playonion.sbs"
}

class onionhd : VidSrcExtractor() {
    override val mainUrl = "https://onionhd.buzz"
}

class Luluvdo : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

class Lulust : StreamWishExtractor() {
    override val mainUrl = "https://lulu.st"
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    @SuppressLint("SuspiciousIndentation")
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    ExtractorLink(
                        this.name,
                        this.name,
                        link,
                        referer ?: "",
                        Qualities.Unknown.value,
                        type = INFER_TYPE
                    )
                )
            }
        }
        return null
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    override val mainUrl = "https://akamaicdn.life"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val mappers = res.selectFirst("script:containsData(sniff\\()")?.data()?.substringAfter("sniff(")
            ?.substringBefore(");") ?: return
        val ids = mappers.split(",").map { it.replace("\"", "") }
        Log.d("Phisher", url)
        val header = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "*/*",
            "Referer" to url
        )
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1",
                url,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = header
            )
        )
    }
}

open class VidSrcExtractor : ExtractorApi() {
    override val name = "VidSrc"
    override val mainUrl = "https://vidsrc.net"
    open val apiUrl = "https://flickersky.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframedoc = app.get(url).document

        val srcrcpList =
            iframedoc.select("div.serversList > div.server").mapNotNull {
                val datahash = it.attr("data-hash") ?: return@mapNotNull null
                val rcpLink = "$apiUrl/rcp/$datahash"
                val rcpRes = app.get(rcpLink, referer = apiUrl).text
                val srcrcpLink =
                    Regex("src:\\s*'(.*)',").find(rcpRes)?.destructured?.component1()
                        ?: return@mapNotNull null
                "https:$srcrcpLink"
            }

        srcrcpList.amap { server ->
            val res = app.get(server, referer = apiUrl)
            if (res.url.contains("/prorcp")) {
                val encodedElement = res.document.select("div#reporting_content+div")
                val decodedUrl =
                    decodeUrl(encodedElement.attr("id"), encodedElement.text()) ?: return@amap

                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        decodedUrl,
                        apiUrl,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else {
                loadExtractor(res.url, url, subtitleCallback, callback)
            }
        }
    }

    private fun decodeUrl(encType: String, url: String): String? {
        return when (encType) {
            "NdonQLf1Tzyx7bMG" -> bMGyx71TzQLfdonN(url)
            "sXnL9MQIry" -> Iry9MQXnLs(url)
            "IhWrImMIGL" -> IGLImMhWrI(url)
            "xTyBxQyGTA" -> GTAxQyTyBx(url)
            "ux8qjPHC66" -> C66jPHx8qu(url)
            "eSfH1IRMyL" -> MyL1IRSfHe(url)
            "KJHidj7det" -> detdj7JHiK(url)
            "o2VSUnjnZl" -> nZlUnj2VSo(url)
            "Oi3v1dAlaM" -> laM1dAi3vO(url)
            "TsA2KGDGux" -> GuxKGDsA2T(url)
            "JoAHUMCLXV" -> LXVUMCoAHJ(url)
            else -> null
        }
    }

    private fun bMGyx71TzQLfdonN(a: String): String {
        val b = 3
        val c = mutableListOf<String>()
        var d = 0
        while (d < a.length) {
            c.add(a.substring(d, minOf(d + b, a.length)))
            d += b
        }
        return c.reversed().joinToString("")
    }

    @SuppressLint("NewApi")
    private fun Iry9MQXnLs(a: String): String = String(Base64.getDecoder().decode(a))

    @TargetApi(Build.VERSION_CODES.O)
    private fun IGLImMhWrI(a: String): String {
        var b = ""
        for (c in a.toCharArray()) {
            val d = c.code
            if (d in 846..1000) b += c.toString() else b += Character.toString((d - 1).toChar())
        }
        return String(Base64.getDecoder().decode(b))
    }

    private fun GTAxQyTyBx(a: String): String {
        val b = 4
        val c = mutableListOf<String>()
        var d = 0
        while (d < a.length) {
            c.add(a.substring(d, minOf(d + b, a.length)))
            d += b
        }
        return c.reversed().joinToString("")
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun C66jPHx8qu(a: String): String {
        var b = ""
        for (c in a.toCharArray()) b += Character.toString(c + 1)
        return String(Base64.getDecoder().decode(b))
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun MyL1IRSfHe(a: String): String {
        var b = ""
        for (c in a.toCharArray()) {
            val d = c.code
            if (d in 846..1000) b += c.toString() else b += Character.toString((d - 2).toChar())
        }
        return String(Base64.getDecoder().decode(b))
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun detdj7JHiK(a: String): String = String(Base64.getDecoder().decode(a.reversed()))

    private fun nZlUnj2VSo(a: String): String {
        val b = 2
        val c = mutableListOf<String>()
        var d = 0
        while (d < a.length) {
            c.add(a.substring(d, minOf(d + b, a.length)))
            d += b
        }
        return c.reversed().joinToString("")
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun laM1dAi3vO(a: String): String = String(Base64.getDecoder().decode(a.reversed()))

    @TargetApi(Build.VERSION_CODES.O)
    private fun GuxKGDsA2T(a: String): String = String(Base64.getDecoder().decode(a))

    @TargetApi(Build.VERSION_CODES.O)
    private fun LXVUMCoAHJ(a: String): String {
        var b = ""
        for (c in a.toCharArray()) b += Character.toString(c + 3)
        return String(Base64.getDecoder().decode(b))
    }
}
