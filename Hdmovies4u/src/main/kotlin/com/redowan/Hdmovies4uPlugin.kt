package com.redowan

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.StreamWishExtractor

@CloudstreamPlugin
class Hdmovies4uPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Hdmovies4u())
        registerExtractorAPI(Voe())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(Drivetot())

    }
}
