package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Movierulzhd())
    //    registerMainAPI(Hdmovie2())
        registerExtractorAPI(FMHD())
        registerExtractorAPI(VidStack())
        registerExtractorAPI(VidSrcTo())
        registerExtractorAPI(Server1uns())
        registerExtractorAPI(Akamaicdn())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(FMX())
        registerExtractorAPI(Lulust())
        registerExtractorAPI(Playonion())
        registerExtractorAPI(Movierulz())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(Gofile())
        
    }
}