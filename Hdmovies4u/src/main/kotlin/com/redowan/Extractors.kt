package com.redowan

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

open class wishonly : ExtractorApi() {
    override val name = "wishonly"
    override val mainUrl = "https://wishonly.site" // Replace with the main URL if needed
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = app.get(url).document

        val extractorLinks = mutableListOf<ExtractorLink>()

        // 1. Direct Download Link Extraction
        val downloadLink = document.select("a.btn")
            .firstOrNull { it.text().contains("Download") }
            ?.attr("href")

        if (downloadLink != null) {
            extractorLinks.add(
                ExtractorLink(
                    name,
                    "Direct Download",
                    downloadLink,
                    referer ?: url,
                    quality = getQualityFromName(downloadLink),
                    isM3u8 = false
                )
            )
        }

        // 2. Iframe Embed Link Extraction
        val iframeLink = document.select("iframe").firstOrNull()?.attr("src")

        if (iframeLink != null) {
            if (iframeLink.contains("m3u8")) {
                // If the iframe link is an M3U8 playlist
                M3u8Helper.generateM3u8(
                    name,
                    iframeLink,
                    referer ?: url
                ).forEach { extractorLinks.add(it) }
            } else {
                // Treat it as a regular embed link
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

        // 3. JWPlayer Extraction (based on the HTML structure)
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
                } else {
                    // Assuming it's a direct video link
                    extractorLinks.add(
                        ExtractorLink(
                            name,
                            "JWPlayer Link",
                            file,
                            referer ?: url,
                            quality = getQualityFromName(file),
                            isM3u8 = file.endsWith(".m3u8")
                        )
                    )
                }
            }
        }

        // 4. VAST tag extraction
        val vastScript = document.select("script").find {
            it.data().contains("vast") && it.data().contains("half-page-ad")
        }?.data()

        if (vastScript != null) {
            val vastTag = Regex("half-page-ad=(.*?)\"").find(vastScript)?.groupValues?.get(1)
            if (vastTag != null) {
                extractorLinks.add(
                    ExtractorLink(
                        name,
                        "VAST Tag",
                        vastTag,
                        referer ?: url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }
        }

        return extractorLinks.ifEmpty { null }
    }
}

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