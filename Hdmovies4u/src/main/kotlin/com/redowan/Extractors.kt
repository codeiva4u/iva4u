package com.redowan

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Helper function to get the document
private suspend fun getDocument(url: String, referer: String? = null, headers: Map<String, String> = mapOf()): Document {
    return Jsoup.parse(app.get(url, referer = referer, headers = headers).text)
}

open class Drivetot : ExtractorApi() {
    override var name: String = "DriveTOT"
    override var mainUrl: String = "https://drivetot.dad"
    override val requiresReferer = false

    // Helper function to decode the URL
    private fun String.toDrivetot(): String =
        base64Decode(this.substringAfterLast('/'))

    // Data class to hold the response from the POST request
    data class Drivetot(
        @JsonProperty("file_code") val file_code: String,
        @JsonProperty("file_name") val file_name: String,
        @JsonProperty("success") val success: Boolean
    )

    // Function to resolve the actual download link
    private suspend fun drivetotResolver(
        url: String,
        source: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = getDocument(url, referer = referer).select("input[name=arun]").attr("value")
        val postUrl = "$source/dl"
        val response = app.post(
            postUrl,
            referer = referer,
            data = mapOf("arun" to token),
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        ).text
        val data = parseJson<Drivetot>(response)
        callback.invoke(
            ExtractorLink(
                name,
                name,
                "https://dl.drivetot.dad/get_direct_link/${data.file_code}/${data.file_name.replace(" ", "%20")}",
                referer,
                Qualities.Unknown.value,
                false
            )
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val source = when {
            url.contains("drivetot.dad") -> "https://drivetot.dad"
            url.contains("drivetot.in") -> "https://drivetot.in"
            else -> "https://drivetot.mom"
        }
        safeApiCall {
            val base64 = url.toDrivetot()
            val getUrl = "$source/scanjs/$base64"
            drivetotResolver(getUrl, source, referer ?: getUrl, callback)
        }
    }
}

open class Voe : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDocument(url, referer = referer)

        doc.select("source").firstOrNull()?.let {
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    it.attr("src"),
                    referer ?: mainUrl,
                    getQualityFromName(it.attr("label")),
                )
            )
        }

        doc.select("li.linkserver").firstOrNull { it.attr("data-video").contains(".m3u8") }?.let {
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    it.attr("data-video"),
                    referer ?: mainUrl,
                    Qualities.Unknown.value,
                    it.attr("data-video").contains(".m3u8")
                )
            )
        }
    }
}