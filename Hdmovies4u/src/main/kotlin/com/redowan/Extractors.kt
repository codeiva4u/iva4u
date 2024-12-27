package com.redowan

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
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

    // Data class to hold the response from the POST request (if needed)
    // Update this if the API response format changes
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
        // First GET request to /scanjs
        val response1 = app.get(url, referer = referer)
        println("DrivetotResolver: First response code: ${response1.code}")
        println("DrivetotResolver: First response: ${response1.text}")

        // Check if the response is a redirect (status code 3xx)
        if (response1.code in 300..399) {
            val redirectUrl = response1.headers["location"]
            println("DrivetotResolver: Redirect URL: $redirectUrl")

            // Check if redirect URL is a Telegram link, HubCloud or FileBee
            if (redirectUrl?.contains("telegram.me") == true) {
                println("DrivetotResolver: Telegram link detected")
                callback.invoke(
                    ExtractorLink(
                        name,
                        "Telegram",
                        redirectUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                return // Stop further processing for Telegram links
            } else if (redirectUrl?.contains("hubcloud.club") == true) {
                println("DrivetotResolver: HubCloud link detected")
                callback.invoke(
                    ExtractorLink(
                        name,
                        "HubCloud",
                        redirectUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                return
            } else if (redirectUrl?.contains("filebee.xyz") == true) {
                println("DrivetotResolver: FileBee link detected")
                callback.invoke(
                    ExtractorLink(
                        name,
                        "FileBee",
                        redirectUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                return
            } else {
                println("DrivetotResolver: Unsupported redirect URL: $redirectUrl")
            }
        } else if (response1.code == 200 && url.contains("https://wishonly.site/player")) {
            println("DrivetotResolver: wishonly.site detected")
            val doc = response1.document
            // val script = doc.select("script:containsData(jwplayerOptions)").firstOrNull()?.data() ?: return
            // val master = Regex("file: \"(.*?)\"").find(script)?.groupValues?.get(1) ?: return

            callback.invoke(
                ExtractorLink(
                    name,
                    "Wishonly",
                    url,
                    referer,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )

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

open class HubCloud : ExtractorApi() {
    override var name = "HubCloud"
    override var mainUrl = "https://hubcloud.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDocument(url, referer = referer)
        val script = doc.select("script:containsData(jwplayerOptions)").firstOrNull()?.data() ?: return
        val playlistUrl = Regex("file:\"(.*?)\"").find(script)?.groupValues?.get(1)
        println("HubCloud: Playlist URL: $playlistUrl")

        if (playlistUrl != null) {
            M3u8Helper.generateM3u8(
                name,
                playlistUrl,
                referer ?: url
            ).forEach(callback)
        } else {
            println("HubCloud: Playlist URL not found")
        }
    }
}