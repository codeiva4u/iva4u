package com.movies4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Movies4uPlugin: BasePlugin() {
    override fun load() {
        // Main Provider
        registerMainAPI(Movies4uProvider())

        // Download Extractors (Direct Downloads Only)
        registerExtractorAPI(M4uLinks())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(PixelDrainDev())
    }
}
