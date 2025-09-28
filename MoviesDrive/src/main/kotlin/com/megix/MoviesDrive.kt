package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesDrive: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MoviesDriveProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlix3())
        registerExtractorAPI(GDFlix7())
        registerExtractorAPI(GDFlix2())
        registerExtractorAPI(GDLink())
        //registerExtractorAPI(Hubdrive())
        registerExtractorAPI( PixelDrain())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudBz())
        registerExtractorAPI(HubCloudInk())
        registerExtractorAPI(HubCloudArt())
        registerExtractorAPI(HubCloudDad())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Gofile())
    }
}
