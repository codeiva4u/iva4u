package com.bolly4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Bolly4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Bolly4uProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(FastDLServer())
        registerExtractorAPI(FxLinks())
        registerExtractorAPI(LinksMod())
    }
}