package com.redowan

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
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
        // First GET request
        val response1 = app.get(url, referer = referer)
        println("DrivetotResolver: First response: ${response1.text}")

        // Check if the response is a redirect (status code 3xx)
        if (response1.code in 300..399) {
            val redirectUrl = response1.headers["location"]
            println("DrivetotResolver: Redirect URL: $redirectUrl")

            // Second GET request to the redirected URL
            val response2 = app.get(redirectUrl!!, referer = url)
            println("DrivetotResolver: Second response: ${response2.text}")

            val doc = response2.document
            val token = doc.select("input[name=arun]").attr("value")
            println("DrivetotResolver: Token: $token")

            val postUrl = "$source/dl"
            println("DrivetotResolver: postUrl: $postUrl")
            val response3 = app.post(
                postUrl,
                referer = redirectUrl,
                data = mapOf("arun" to token),
                headers = mapOf("x-requested-with" to "XMLHttpRequest")
            ).text
            println("DrivetotResolver: response: $response3")
            val data = parseJson<Drivetot>(response3)
            println("DrivetotResolver: data: $data")

            if (data.success) {
                val fileUrl = "https://dl.drivetot.dad/get_direct_link/${data.file_code}/${data.file_name.replace(" ", "%20")}"
                println("DrivetotResolver: fileUrl: $fileUrl")
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        fileUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            } else {
                println("DrivetotResolver: File not found")
            }
        } else {
            println("DrivetotResolver: No redirect found")
        }
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

// Voe Extractor (No changes needed here)
open class Voe : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true

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

        doc.select("li.linkserver").map {
            it.attr("data-video")
        }.apmap {
            safeApiCall {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        it,
                        referer ?: url,
                        Qualities.Unknown.value,
                        isM3u8 = it.endsWith(".m3u8")
                    )
                )
            }
        }
    }
}