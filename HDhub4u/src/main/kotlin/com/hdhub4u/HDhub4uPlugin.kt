package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(PixelDrainDev())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(VidHidePro())
    }
}