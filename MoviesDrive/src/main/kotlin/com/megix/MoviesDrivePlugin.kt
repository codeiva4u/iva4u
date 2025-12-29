package com.megix

import android.content.Context
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(MoviesDriveProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixDev())  // New: gdflix.dev domain support
        registerExtractorAPI(HubCloud())

    }

}


