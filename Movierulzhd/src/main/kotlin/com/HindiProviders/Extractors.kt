package com.Phisher98

import android.annotation.SuppressLint
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class FileMoon : ExtractorApi() {
    override val name: String = "FileMoon"
    override val mainUrl: String = "https://fle-rvd0i9o8-moo.com"
    override val requiresReferer = true

    @SuppressLint("SuspiciousIndentation")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // वेबपेज को पार्स करें
        val document: Document = Jsoup.connect(url).referrer(referer ?: mainUrl).get()

        // स्ट्रीमिंग लिंक निकालें (यदि यह एक ब्लॉब URL है, तो इसे संभालें)
        val videoElement: Element? = document.selectFirst("video.jw-video")
        val streamingLink: String? = videoElement?.attr("src")

        // डाउनलोड लिंक निकालें (यदि यह मौजूद है)
        val downloadLink: String? = document.selectFirst("a.download-link")?.attr("href")

        // जावास्क्रिप्ट एलिमेंट को पार्स करें
        val scriptTag: Element? = document.selectFirst("script:containsData(sources: [)")
        val scriptContent: String = scriptTag?.data() ?: return

        // जावास्क्रिप्ट से वीडियो लिंक निकालें
        val videoLinkPattern: Pattern = Pattern.compile("sources: \\[\\{file: \"(.*?)\"")
        val matcher = videoLinkPattern.matcher(scriptContent)
        val videoLink: String? = if (matcher.find()) matcher.group(1) else return

        // वीडियो लिंक को रिटर्न करें
        callback.invoke(
            ExtractorLink(
                this.name,
                "FileMoon Video",
                videoLink,
                url,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8 // यह मानते हुए कि लिंक M3U8 फॉर्मेट में है
            )
        )

        // निकाले गए लिंक प्रिंट करें
        println("Streaming Link: $streamingLink")
        println("Download Link: $downloadLink")
    }
}

class FMHD : Filesim() {
    override val name = "FMHD"
    override var mainUrl = "https://fmhd.bar/"
    override val requiresReferer = true
}

class Playonion : Filesim() {
    override val mainUrl = "https://playonion.sbs"
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
        val response = app.get(url,referer=mainUrl).document
        val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
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
        val header= mapOf("User-Agent" to "PostmanRuntime/7.43.0","Accept" to "*/*","Referer" to url)
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                headers = header
            )
        )
    }
}
class Mocdn:Akamaicdn(){
   override val name = "Mocdn"
   override val mainUrl = "https://mocdn.art"
}
