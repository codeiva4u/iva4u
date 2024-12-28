package com.redowan

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

class FsLFastDl : ExtractorApi() {
    override val name = "FsLFastDl"
    override val mainUrl = "https://fsl.fastdl.lol"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        val fslLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download [FSL Server]") }
            ?.attr("href")

        if (fslLink != null && fslLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    name,
                    "FSL Server",
                    fslLink,
                    referer ?: url,
                    quality = getQualityFromName(fslLink), // You might need to refine quality extraction
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        // Iframe Embed Link Extraction
        val iframeLink = document.select("iframe").firstOrNull()?.attr("src")

        if (iframeLink != null && iframeLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    name,
                    "PixelDrain Embed",
                    iframeLink,
                    referer ?: url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
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
                    name,
                    "PixelDrain Download",
                    downloadLink,
                    referer ?: url,
                    quality = Qualities.Unknown.value, // You might need to refine quality extraction
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}

class Technorozen : ExtractorApi() {
    override val name = "Technorozen"
    override val mainUrl = "https://gpdl2.technorozen.workers.dev"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = Jsoup.parse(app.get(url).text)
        val extractorLinks = mutableListOf<ExtractorLink>()

        val downloadLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download [Server : 10Gbps]") }
            ?.attr("href")

        if (downloadLink != null && downloadLink.startsWith(mainUrl)) {
            extractorLinks.add(
                ExtractorLink(
                    name,
                    "Technorozen Download",
                    downloadLink,
                    referer ?: url,
                    quality = Qualities.Unknown.value, // You might need to refine quality extraction
                    isM3u8 = false
                )
            )
        }

        return extractorLinks.ifEmpty { null }
    }
}