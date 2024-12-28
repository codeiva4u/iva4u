package com.redowan

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

open class FilePress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://new1.filepress.life"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document

        // "div.col-md-12.col-sm-12.col-xs-12.download-block a" से लिंक निकालें
        document.select("div.col-md-12.col-sm-12.col-xs-12.download-block a").mapNotNull { element ->
            val link = element.attr("href")

            // लिंक वैध है तो, उसे ExtractorLink के रूप में प्रदान करें
            if (link.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "FilePress",
                        url = link,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = link.contains(".m3u8") // m3u8 लिंक के लिए
                    )
                )
            }
        }
    }
}

open class HubCloud : ExtractorApi() {
    override val name: String = "HubCloud"
    override val mainUrl: String = "https://hubcloud.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document

        // "Download Link Generated" ढूंढें
        if (document.select("p.text-success").text().contains("Download Link Generated")) {
            // "Copy Share Link" बटन के पास इनपुट फील्ड से लिंक निकालें
            val shareLink = document.select("input#ilink").attr("value")

            // लिंक वैध है तो, उसे ExtractorLink के रूप में प्रदान करें
            if (shareLink.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "HubCloud",
                        url = shareLink,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }

            // "a.btn" से अन्य डाउनलोड लिंक निकालें
            document.select("a.btn").mapNotNull { element ->
                val link = element.attr("href")
                val linkText = element.text()

                if (link.isNotBlank()) {
                    when {
                        link.contains("fsl.fastdl.lol") -> {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "HubCloud - FSL Server",
                                    url = link,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = link.contains(".m3u8")
                                )
                            )
                        }
                        link.contains("aws-es.mixis94992.workers.dev") -> {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "HubCloud - AWS Server",
                                    url = link,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = link.contains(".m3u8")
                                )
                            )
                        }
                        link.contains("gpdl2.technorozen.workers.dev") -> {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "HubCloud - 10Gbps Server",
                                    url = link,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = link.contains(".m3u8")
                                )
                            )
                        }
                        link.contains("driveseed.org") || link.contains("driveleech.org") -> {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "HubCloud - DriveLeech",
                                    url = link,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = link.contains(".m3u8")
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}