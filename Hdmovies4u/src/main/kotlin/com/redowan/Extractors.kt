package com.redowan

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import android.util.Log

// DriveTot Extractor
class DriveTot : ExtractorApi() {
    override val name = "DriveTot"
    override val mainUrl = "https://drivetot.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document

        doc.select("a.uploadever").forEach { element ->
            val extractedUrl = element.attr("href")
                .replace("https://drivetot.dad/scanjs/", "")
                .let {
                    try {
                        String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                    } catch (e: Exception) {
                        Log.e(name, "Base64 decoding failed: $e")
                        ""
                    }
                }

            if (extractedUrl.isNotEmpty()) {
                val quality = getQualityFromName(element.text())
                val extractedName = element.text()

                callback.invoke(
                    ExtractorLink(
                        name = "$name: $extractedName",
                        source = name,
                        url = extractedUrl,
                        referer = mainUrl,
                        quality = quality,
                        isM3u8 = false
                    )
                )
            }
        }
    }
}

// HubCloud Extractor
open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.club" // or https://hubcloud.ink, https://hubcloud.day, https://hubcloud.tel
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    videoUrl,
                    referer = url,
                    quality = Qualities.Unknown.value
                )
            )
        }

        // अन्य डाउनलोड लिंक्स निकालें और loadExtractor का उपयोग करें
        val downloadLinks = doc.select("div.card-body a[href]")
        for (element in downloadLinks) {
            val linkUrl = element.attr("href")
            val linkText = element.text()

            // FSL, AWSE, और GPDL के लिए loadExtractor का उपयोग करें
            when {
                linkUrl.startsWith("https://fsl.fastdl.lol/") -> {
                    loadExtractor(linkUrl, referer, subtitleCallback, callback)
                }
                linkUrl.startsWith("https://aws-es.mixis94992.workers.dev/") -> {
                    loadExtractor(linkUrl, referer, subtitleCallback, callback)
                }
                linkUrl.startsWith("https://gpdl2.technorozen.workers.dev/") -> {
                    loadExtractor(linkUrl, referer, subtitleCallback, callback)
                }
                else -> {
                    // अन्य लिंक के लिए, यदि कोई हो, सीधे ExtractorLink बनाएं
                    callback.invoke(
                        ExtractorLink(
                            name = "${this.name} $linkText",
                            source = this.name,
                            url = linkUrl,
                            referer = url,
                            quality = getQualityFromName(linkText),
                            isM3u8 = linkUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }
    }

    // FSL (fsl.fastdl.lol) Extractor
    private class FSLE : ExtractorApi() {
        override val name = "FSLE"
        override val mainUrl = "https://fsl.fastdl.lol"
        override val requiresReferer = false

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            referer?.let {
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = url,
                    referer = it,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            }?.let {
                callback.invoke(
                    it
                )
            }
        }
    }

    // AWSE (aws-es.mixis94992.workers.dev) Extractor
    private class AWSE : ExtractorApi() {
        override val name = "AWSE"
        override val mainUrl = "https://aws-es.mixis94992.workers.dev"
        override val requiresReferer = false
        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            referer?.let {
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = url,
                    referer = it,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            }?.let {
                callback.invoke(
                    it
                )
            }
        }
    }

    // GPDL (gpdl2.technorozen.workers.dev) Extractor
    private class GPDL : ExtractorApi() {
        override val name = "GPDL"
        override val mainUrl = "https://gpdl2.technorozen.workers.dev"
        override val requiresReferer = false
        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            referer?.let {
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = url,
                    referer = it,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            }?.let {
                callback.invoke(
                    it
                )
            }
        }
    }
}

// FilePress Extractor
class FilePress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://new1.filepress.life"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document

        // Extract all a tags with href attribute
        val links = doc.select("a[href]")
        for (link in links) {
            val linkUrl = link.attr("href")
            val linkText = link.text()

            when {
                linkUrl.startsWith("https://v1.sdsp.xyz/embed/movie/") -> {
                    // If it's a video URL, create an ExtractorLink directly
                    callback.invoke(
                        ExtractorLink(
                            name = "${this.name} Watch Now",
                            source = this.name,
                            url = linkUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }

                else -> {
                    // अन्य प्रकार के लिंक्स को हैंडल करें, यदि आवश्यक हो, या उन्हें अनदेखा करें
                    println("FilePress: Found other link (not handled): $linkUrl")
                    // क्वालिटी निकालने का प्रयास करें
                    val quality = getQualityFromName(linkText)
                    callback.invoke(
                        ExtractorLink(
                            name = "${this.name} $linkText",
                            source = this.name,
                            url = linkUrl,
                            referer = url,
                            quality = quality,
                            isM3u8 = linkUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }
    }
}