package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@Suppress("unused")
@CloudstreamPlugin
class Hdmovies4uPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Hdmovies4u())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(Drivetot())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudlol())
        registerExtractorAPI(HubCloudClub())
        registerExtractorAPI(PixelDrain())

    }
}
