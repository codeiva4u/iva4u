package com.redowan


import android.content.Context
import com.Phisher98.Hdmovies4u
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.megix.Driveleech
import com.megix.Driveseed

@CloudstreamPlugin
class Hdmovies4uPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hdmovies4u())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Voe())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())

    }
}
