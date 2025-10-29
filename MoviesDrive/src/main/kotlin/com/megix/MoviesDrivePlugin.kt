package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(MoviesDriveProvider())
        
        // Register HubCloud Extractors (4 separate servers)
        registerExtractorAPI(HubCloudPixelServer())
        registerExtractorAPI(HubCloudServer10Gbps())
        registerExtractorAPI(HubCloudFSLServer())
        registerExtractorAPI(HubCloudMegaServer())
        
        // Register GDFlix Extractors (2 separate servers)
        registerExtractorAPI(GDFlixInstantDL10GBPS())
        registerExtractorAPI(GDFlixPixelDrainDL20MBs())
        
        // Register Legacy HubCloud domains (for backward compatibility)
        registerExtractorAPI(HubCloudInk())
        registerExtractorAPI(HubCloudArt())
        registerExtractorAPI(HubCloudDad())
        registerExtractorAPI(HubCloudBz())
        
        // Register Legacy GDFlix domains (for backward compatibility)
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlix3())
        registerExtractorAPI(GDFlix2())
        registerExtractorAPI(GDFlix7())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixDev())
        
        // Register Other Extractors
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(fastdlserver2())
    }
}
