package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(FastLinks())
        registerExtractorAPI(Gofile())

    }
}
