package com.bollyflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BollyFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BollyFlixProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(FastDLServer())
        registerExtractorAPI(FxLinks())
    }
}
