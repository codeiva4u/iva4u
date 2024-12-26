import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName

open class Voe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    private val linkRegex =
        "(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()
    private val base64Regex = Regex("'.*'")
    private val redirectRegex = Regex("""window.location.href = '([^']+)';""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document

        val script =
            if (!res.select("script").firstOrNull() { it.data().contains("sources =") }?.data()
                    .isNullOrEmpty()
            ) {
                res.select("script").find { it.data().contains("sources =") }?.data()
            } else {
                redirectRegex.find(res.data())?.groupValues?.get(1)?.let { redirectUrl ->
                    app.get(
                        redirectUrl,
                        referer = referer
                    ).document.select("script").find { it.data().contains("sources =") }?.data()
                }
            }

        val link =
            Regex("[\"']hls[\"']:\\s*[\"'](.*)[\"']").find(script ?: return)?.groupValues?.get(1)

        val videoLinks = mutableListOf<String>()

        if (!link.isNullOrBlank()) {
            videoLinks.add(
                when {
                    linkRegex.matches(link) -> link
                    else -> base64Decode(link)
                }
            )
        } else {
            val link2 = base64Regex.find(script)?.value ?: return
            val decoded = base64Decode(link2)
            val videoLinkDTO = parseJson<WcoSources>(decoded)
            videoLinkDTO.let { videoLinks.add(it.toString()) }
        }

        videoLinks.forEach { videoLink ->
            M3u8Helper.generateM3u8(
                name,
                videoLink,
                "$mainUrl/",
                headers = mapOf("Origin" to "$mainUrl/")
            ).forEach(callback)
        }
    }

    data class WcoSources(
        @JsonProperty("VideoLinkDTO") val VideoLinkDTO: String,
    )
}

class AllSetLol : ExtractorApi() {
    override var name = "AllSet.lol"
    override var mainUrl = "https://allset.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, allowRedirects = true).document
        doc.select("div.entry-content > a").forEach {
            val link = it.attr("href")
            val quality = it.previousElementSibling()?.text() ?: ""
            callback.invoke(
                ExtractorLink(
                    name,
                    "$name - $quality",
                    link,
                    mainUrl,
                    getQualityFromName(quality)
                )
            )
        }
    }
}

open class DoodStream : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val script = doc.select("script").find {
            it.data().contains("eval(function(p,a,c,k,e,d)")
        }?.data()
        val unpacked = getAndUnpack(script ?: return)

        val (videoUrl, quality) = Regex(""""file":"(.*?)","label":"(.*?)"""").find(unpacked)?.destructured ?: return
        callback.invoke(
            ExtractorLink(
                name,
                name,
                videoUrl,
                referer = url,
                quality = getQualityFromName(quality),
            )
        )
    }

    companion object {
    }
}
