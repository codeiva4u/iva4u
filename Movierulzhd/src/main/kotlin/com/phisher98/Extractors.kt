package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Cherry Extractor for cherry.upns.online
class CherryExtractor : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document
            
            // Try to extract video sources from various script tags
            val scripts = document.select("script")
            
            for (script in scripts) {
                val scriptData = script.data()
                
                // Look for m3u8 URLs
                if (scriptData.contains(".m3u8")) {
                    val m3u8Regex = """(https?://[^\s"']+\.m3u8[^\s"']*)""".toRegex()
                    m3u8Regex.findAll(scriptData).forEach { match ->
                        val m3u8Url = match.groupValues[1]
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = m3u8Url,
                            referer = url,
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    }
                }
                
                // Look for mp4 URLs
                if (scriptData.contains(".mp4")) {
                    val mp4Regex = """(https?://[^\\s"']+\\.mp4[^\\s"']*)""".toRegex()
                    mp4Regex.findAll(scriptData).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                videoUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            
            // Try WebView extraction as fallback
            val webViewResponse = app.get(
                url = url,
                interceptor = WebViewResolver(
                    Regex("""\.(m3u8|mp4|mkv)""")
                )
            )
            
            webViewResponse.url.let { finalUrl ->
                when {
                    finalUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = finalUrl,
                            referer = url,
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    }
                    finalUrl.contains(".mp4") || finalUrl.contains(".mkv") -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                finalUrl
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("CherryExtractor", "Error: ${e.message}")
        }
    }
}
