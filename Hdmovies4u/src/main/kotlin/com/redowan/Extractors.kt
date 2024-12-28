package com.redowan

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

class wishonly : ExtractorApi() {
    override val name = "wishonly"
    override val mainUrl = "https://wishonly.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        // Iframe Embed Link Extraction
        val iframeLink = document.select("iframe").firstOrNull()?.attr("src")

        if (iframeLink != null) {
            // If the iframe source is from "https://wishonly.site" and attempt to extract from it
            if (iframeLink.startsWith(mainUrl)) {
                val iframeDocument = Jsoup.parse(app.get(iframeLink).text)
                // Assuming the video link in the iframe is an M3U8 link
                val videoLink = iframeDocument.select("script").mapNotNull { script ->
                    Regex("file:\\s*\"(.*?)\"").find(script.data())?.groupValues?.get(1)
                }.firstOrNull { it.endsWith(".m3u8") }

                if (videoLink != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        videoLink,
                        iframeLink
                    ).forEach { extractorLinks.add(it) }
                }
            } else {
                // If the iframe source is not from "https://wishonly.site", add it as a general embed
                extractorLinks.add(
                    ExtractorLink(
                        name,
                        "Iframe Embed",
                        iframeLink,
                        referer ?: url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }
        }

        // JWPlayer Extraction (updated to extract from script)
        val script = document.select("script").find {
            it.data().contains("jwplayer") && it.data().contains("file")
        }?.data()

        if (script != null) {
            val file = Regex("file:\\s*\"(.*?)\"").find(script)?.groupValues?.get(1)
            if (file != null) {
                if (file.endsWith(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        file,
                        referer ?: url
                    ).forEach { extractorLinks.add(it) }
                }
            }
        }

        return extractorLinks.ifEmpty { null }
    }
}

// FsLFastDl Extractor
class FsLFastDl : ExtractorApi() {
    override val name = "FsLFastDl"
    override val mainUrl = "https://fsl.fastdl.lol"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url, referer = referer).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        val fslLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download [FSL Server]") }
            ?.attr("href")

        if (fslLink != null && fslLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    source = name,
                    name = "FSL Server",
                    url = fslLink,
                    referer = referer ?: url,
                    quality =  Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}

// PixelDrain Extractor
class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url, referer = referer).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        // Iframe Embed Link Extraction
        val iframeLink = document.select("iframe").firstOrNull()?.attr("src")

        if (iframeLink != null && iframeLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    source = name,
                    name = "PixelDrain Embed",
                    url = iframeLink,
                    referer = referer ?: url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false,
                    isDash = false
                )
            )
        }

        // Direct Download Link Extraction from button
        val downloadLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download [PixelServer") }
            ?.attr("href")

        if (downloadLink != null && downloadLink.startsWith("https://pixeldra.in/api/file/")) {
            extractorLinks.add(
                ExtractorLink(
                    source = name,
                    name = "PixelDrain Download",
                    url = downloadLink,
                    referer = referer ?: url,
                    quality = getQualityFromName(downloadLink),
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}

// Technorozen Extractor
class Technorozen : ExtractorApi() {
    override val name = "Technorozen"
    override val mainUrl = "https://gpdl2.technorozen.workers.dev"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url, referer = referer).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        val downloadLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download [Server : 10Gbps]") }
            ?.attr("href")

        if (downloadLink != null && downloadLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    source = name,
                    name = "Technorozen Download",
                    url = downloadLink,
                    referer = referer ?: url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}