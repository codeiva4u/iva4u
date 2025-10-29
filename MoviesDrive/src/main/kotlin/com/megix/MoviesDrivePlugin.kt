package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(MoviesDriveProvider())
        
        // Register extractors
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudInk())
        registerExtractorAPI(HubCloudArt())
        registerExtractorAPI(HubCloudDad())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(fastdlserver2())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlix3())
        registerExtractorAPI(GDFlix2())
        registerExtractorAPI(GDFlix7())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixDev())
    }
}
