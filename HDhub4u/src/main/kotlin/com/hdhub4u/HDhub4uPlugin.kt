package com.hdhub4u

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.app


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(StreamTape())
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