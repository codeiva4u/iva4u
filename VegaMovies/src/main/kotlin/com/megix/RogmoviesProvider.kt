package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

class RogmoviesProvider : VegaMoviesProvider() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://rogmovies.vip"
    override var name = "Rogmovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("rogmovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/hindi-dubbed-movies/" to "South Movies",
        "$mainUrl/bollywood/" to "Bollywood",
        "$mainUrl/dual-audio-movies/" to "Movies",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney Plus Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/jiohotstar/page/%d/" to "Jio Hotstar",
        "$mainUrl/category/web-series/zee5-originals/page/%d/" to "Zee5 Original",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original",
    )
}
