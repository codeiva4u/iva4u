package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MoviesDriveProvider())
//        registerExtractorAPI(Driveseed())
//        registerExtractorAPI(Driveleech())
//        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudlol())
        registerExtractorAPI(HubCloudClub())
        registerExtractorAPI(fastdlserver())
//        registerExtractorAPI(GDFlix3())
        registerExtractorAPI(GDFlix2())
//        registerExtractorAPI(GDFlix4())
        registerExtractorAPI(GDFlix())
//        registerExtractorAPI(FastLinks())
//        registerExtractorAPI(Photolinx())
//        registerExtractorAPI(WLinkFast())
//        registerExtractorAPI(Sendcm())
    }
}
