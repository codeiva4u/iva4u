package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(FourKHDHub())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(PixelDrainDev())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(HDStream4u())
        registerExtractorAPI(Hubstream())
    }
}