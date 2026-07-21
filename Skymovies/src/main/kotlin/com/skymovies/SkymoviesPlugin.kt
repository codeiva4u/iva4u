package com.skymovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SkymoviesPlugin: BasePlugin() {
    override fun load() {
        // Main Provider
        registerMainAPI(SkymoviesProvider())

        // Download Extractors (Direct Downloads Only)
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(PixelDrainDev())
    }
}
