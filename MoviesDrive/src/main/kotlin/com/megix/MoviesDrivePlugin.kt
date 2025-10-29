package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(MoviesDriveProvider())
        
        // Register HubCloud extractors
        registerExtractorAPI(HubCloudExtractor())
        registerExtractorAPI(PixelDrainExtractor())
        registerExtractorAPI(InstantDLExtractor())
        
        // Register GDFlix extractors
        registerExtractorAPI(GDFlixExtractor())
        registerExtractorAPI(FSLServerExtractor())
        registerExtractorAPI(MegaServerExtractor())
    }
}
