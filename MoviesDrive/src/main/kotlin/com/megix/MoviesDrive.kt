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
        registerExtractorAPI(HubCloudBz())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlix2())
        registerExtractorAPI(GDFlix3())
        registerExtractorAPI(GDFlix7())
        registerExtractorAPI(GDFlixDev())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(fastdlserver())
    }
}
