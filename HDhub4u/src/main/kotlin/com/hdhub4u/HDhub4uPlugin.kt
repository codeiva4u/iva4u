package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        // Core extractors
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(HUBCDN())
        // Video player extractors (for web series)
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(VidStack())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(PixelDrainDev())
        // StreamWish extractors
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(StreamwishCom())
        registerExtractorAPI(Wishembed())
        registerExtractorAPI(Sfastwish())
        registerExtractorAPI(Flaswish())
    }
}
