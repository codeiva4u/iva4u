package com.moviesdrive

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviesDrivePlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MoviesDriveProvider())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(FilebeeExtractor())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(HUBCDN())
    }
}
