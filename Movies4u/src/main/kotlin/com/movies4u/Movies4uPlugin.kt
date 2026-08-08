package com.movies4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Movies4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Movies4uProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(M4uLinks())
        registerExtractorAPI(FastDLServer())
        registerExtractorAPI(FxLinks())
    }
}
