package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

/**
 * MoviesDrive specific extractors
 * - HubCloud: enumerates all available final servers and returns only final direct links
 * - GDFlix: extracts direct CDN / R2 and PixelDrain links, avoiding any redirector URLs
 */

class HubCloudMd : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Normalize to current hubcloud domain
            val normalized = url.replace(Regex("https?://hubcloud\\.(one|club|fans)", RegexOption.IGNORE_CASE), mainUrl)
            val firstDoc = app.get(normalized, referer = referer).document

            // If landing page has the big Generate Direct Download Link button, follow its target page
            val genHref = firstDoc.selectFirst("#download")?.attr("href")
            val targetDoc: Document = if (!genHref.isNullOrBlank()) {
                app.get(genHref, referer = normalized).document
            } else {
                // Some hubcloud.php links are passed directly – load the page and work with buttons there
                if (normalized.contains("hubcloud.php", true)) app.get(normalized).document else firstDoc
            }

            val size = targetDoc.selectFirst("i#size")?.text().orEmpty()
            val header = targetDoc.selectFirst("div.card-header")?.text().orEmpty()
            val quality = getIndexQuality(header)
            val extras = buildString {
                if (header.isNotBlank()) append("[$header]")
                if (size.isNotBlank()) append("[$size]")
            }

            // Enumerate all server buttons; only emit FINAL links (no intermediate redirectors)
            targetDoc.select("a.btn, a.btn-success, a.btn-danger, a.btn-primary").amap { a ->
                val link = a.attr("href").trim()
                val text = a.text().trim()
                if (link.isBlank()) return@amap

                when {
                    // PixelDrain direct
                    text.contains("Pixel", true) || link.contains("pixeldrain", true) -> {
                        callback(
                            newExtractorLink(
                                name,
                                "Pixeldrain $extras",
                                link,
                                INFER_TYPE
                            ) { this.quality = quality }
                        )
                    }

                    // 10Gbps server: follow redirects until parameter `link=` appears and use only final link
                    text.contains("10Gbps", true) || link.contains("pixel.hubcdn", true) -> {
                        var current = link
                        var final: String? = null
                        repeat(5) {
                            val resp = app.get(current, allowRedirects = false)
                            val loc = resp.headers["location"]
                            if (loc.isNullOrBlank()) return@repeat
                            if (loc.contains("link=")) {
                                final = loc.substringAfter("link=")
                                return@repeat
                            }
                            current = loc
                        }
                        final?.let {
                            callback(
                                newExtractorLink(
                                    "$name 10Gbps",
                                    "$name 10Gbps $extras",
                                    it,
                                    INFER_TYPE
                                ) { this.quality = quality }
                            )
                        }
                    }

                    // FSL Server direct link (tokenized) – already final
                    text.contains("FSL", true) || link.contains("fsl.", true) -> {
                        callback(
                            newExtractorLink(
                                "$name FSL",
                                "$name FSL $extras",
                                link,
                                INFER_TYPE
                            ) { this.quality = quality }
                        )
                    }

                    // S3 Server: JS builds a signed URL; try to obtain it from script or onclick
                    text.contains("S3", true) || link.contains("blockxpiracy", true) -> {
                        // Some pages assign onClick to #s3_redirect; if link already points to s3 we use it
                        val s3 = if (link.contains("googleapis.com") || link.contains("blockxpiracy") || link.contains("s3.")) link else null
                        if (s3 != null) {
                            callback(
                                newExtractorLink(
                                    "$name S3",
                                    "$name S3 $extras",
                                    s3,
                                    INFER_TYPE
                                ) { this.quality = quality }
                            )
                        } else {
                            // Fallback: try to read inline script assignment
                            targetDoc.select("script:containsData(s3_redirect)").firstOrNull()?.data()?.let { data ->
                                Regex("window\\.location\\.href\\s*=\\s*'([^']+)'").find(data)?.groupValues?.getOrNull(1)?.let { s3Url ->
                                    callback(
                                        newExtractorLink(
                                            "$name S3",
                                            "$name S3 $extras",
                                            s3Url,
                                            INFER_TYPE
                                        ) { this.quality = quality }
                                    )
                                }
                            }
                        }
                    }

                    // ZipDisk or other direct mirrors
                    text.contains("ZipDisk", true) -> {
                        callback(
                            newExtractorLink(
                                "$name ZipDisk",
                                "$name ZipDisk $extras",
                                link,
                                INFER_TYPE
                            ) { this.quality = quality }
                        )
                    }

                    // Generic safety: only load known direct hosts, avoid redirectors like hubcloud.php
                    link.startsWith("http", true) && !link.contains("hubcloud.php", true) -> {
                        loadExtractor(link, name, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction error: ${e.message}")
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

class GDFlixMd : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    private fun sameHost(url: String) = try { URI(url).host ?: "" } catch (_: Exception) { "" }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Normalize to any known GDFlix host
            val doc = app.get(url, referer = referer).document

            // 1) Direct cloud download (R2 / fastcdn) button
            doc.select("a.btn[href]").forEach { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                when {
                    // Fast CDN proxy -> final file URL already in query param url=
                    href.contains("fastcdn-dl.pages.dev", true) || text.contains("CLOUD DOWNLOAD", true) -> {
                        callback(
                            newExtractorLink(
                                name,
                                "$name Cloud",
                                href,
                                INFER_TYPE
                            ) { this.quality = Qualities.Unknown.value }
                        )
                    }
                    // PixelDrain direct
                    href.contains("pixeldrain", true) -> {
                        callback(
                            newExtractorLink(
                                name,
                                "$name PixelDrain",
                                href,
                                INFER_TYPE
                            ) { this.quality = Qualities.Unknown.value }
                        )
                    }
                }
            }

            // 2) Fallback: find any obvious direct file endpoints (r2.dev, googleapis, api/file?download)
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.contains("r2.dev", true) || href.contains("googleapis.com", true) || href.contains("/api/file/", true)) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            href,
                            INFER_TYPE
                        ) { this.quality = Qualities.Unknown.value }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction error: ${e.message}")
        }
    }
}