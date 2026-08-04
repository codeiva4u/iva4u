package com.bollyflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BollyFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BollyFlixProvider())
        registerExtractorAPI(FxLinks())
        registerExtractorAPI(FastDLServer())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(FilebeeExtractor())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(HUBCDN())
    }
}
