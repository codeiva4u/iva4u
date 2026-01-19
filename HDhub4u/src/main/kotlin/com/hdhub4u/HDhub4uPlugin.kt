package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(PixelDrainDev())
    }
}