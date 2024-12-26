package com.redowan


import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import android.content.Context
import com.Phisher98.HDMovies4uProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDMovies4uProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDMovies4uProvider())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Voe())

    }
}
