package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

suspend fun getLatestUrl(url: String, source: String): String {
    val link = JSONObject(
        app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
    ).optString(source)
    if (link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val realUrl = url.takeIf {
            try { URI(it).toURL(); true } catch (e: Exception) { Log.e(tag, "Invalid URL: ${e.message}"); false }
        } ?: return

        val baseUrl=getBaseUrl(realUrl)

        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val rawHref = app.get(realUrl).document.select("#download").attr("href")
                if (rawHref.startsWith("http", ignoreCase = true)) {
                    rawHref
                } else {
                    baseUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract href: ${e.message}")
            ""
        }

        if (href.isBlank()) {
            Log.w(tag, "No valid href found")
            return
        }

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }
        val quality = getIndexQuality(header)

        document.select("div.card-body h2 a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()

            when {
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer [FSL Server]",
                            "$referer [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("Download File", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer",
                            "$referer $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("BuzzServer", ignoreCase = true) -> {
                    val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                    val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                    if (dlink.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "$referer [BuzzServer]",
                                "$referer [BuzzServer] $labelExtras",
                                dlink,
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            finalURL
                        ) { this.quality = quality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer S3 Server",
                            "$referer S3 Server $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("FSLv2", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer FSLv2",
                            "$referer FSLv2 $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("Mega Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$referer [Mega Server]",
                            "$referer [Mega Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("10Gbps", ignoreCase = true) -> {
                    var currentLink = link
                    var redirectUrl: String?
                    var redirectCount = 0
                    val maxRedirects = 3

                    while (redirectCount < maxRedirects) {
                        val response = app.get(currentLink, allowRedirects = false)
                        redirectUrl = response.headers["location"]

                        if (redirectUrl == null) {
                            Log.e(tag, "10Gbps: No redirect")
                            return@amap
                        }

                        if ("link=" in redirectUrl) {
                            val finalLink = redirectUrl.substringAfter("link=")
                            callback.invoke(
                                newExtractorLink(
                                    "10Gbps [Download]",
                                    "10Gbps [Download] $labelExtras",
                                    finalLink
                                ) { this.quality = quality }
                            )
                            return@amap
                        }

                        currentLink = redirectUrl
                        redirectCount++
                    }

                    Log.e(tag, "10Gbps: Redirect limit reached ($maxRedirects)")
                    return@amap
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            ""
        }
    }

    fun cleanTitle(title: String): String {
        val parts = title.split(".", "-", "_")

        val qualityTags = listOf(
            "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
            "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV",
            "HD"
        )

        val audioTags = listOf(
            "AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos"
        )

        val subTags = listOf(
            "ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub"
        )

        val codecTags = listOf(
            "x264", "x265", "H264", "HEVC", "AVC"
        )

        val startIndex = parts.indexOfFirst { part ->
            qualityTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        val endIndex = parts.indexOfLast { part ->
            subTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    audioTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                    codecTags.any { tag -> part.contains(tag, ignoreCase = true) }
        }

        return if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
            parts.subList(startIndex, endIndex + 1).joinToString(".")
        } else if (startIndex != -1) {
            parts.subList(startIndex, parts.size).joinToString(".")
        } else {
            parts.takeLast(3).joinToString(".")
        }
    }
}

open class StreamWishExtractor : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://swhoi.com/"
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

open class DoodLaExtractor : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://dood.la"
    override val requiresReferer = false

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/d/", "/e/")
        val req = app.get(embedUrl)
        val host = getBaseUrl(req.url)
        val response0 = req.text
        val md5 = host + (Regex("/pass_md5/[^']*").find(response0)?.value ?: return)
        val trueUrl = app.get(md5, referer = req.url).text + createHashTable() + "?token=" + md5.substringAfterLast("/")
        val quality = Regex("\\d{3,4}p")
            .find(response0.substringAfter("<title>").substringBefore("</title>"))
            ?.groupValues
            ?.getOrNull(0)

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                trueUrl,
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(quality)
            }
        )
    }

    private fun createHashTable(): String {
        return buildString {
            repeat(10) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}