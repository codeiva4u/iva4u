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
        registerExtractorAPI(PixelDrainHubCloud())
        registerExtractorAPI(HubCloud10Gbps())
        registerExtractorAPI(FSLServer())
        registerExtractorAPI(MegaServer())
        
        // Register GDFlix extractors
        registerExtractorAPI(InstantDL10GBPS())
        registerExtractorAPI(PixelDrainGDFlix())
    }
}
