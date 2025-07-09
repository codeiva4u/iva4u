package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        registerExtractorAPI(VidSrcTo())
        registerExtractorAPI(Server1uns())
        registerExtractorAPI(Akamaicdn())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(FMX())
        registerExtractorAPI(Lulust())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(VidStack())
        registerExtractorAPI(Movierulz())
        registerExtractorAPI(Filesim())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Ztreamhub())
        registerExtractorAPI(Movhide())
        registerExtractorAPI(StreamhideCom())
        registerExtractorAPI(StreamhideTo())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(Moviesm4u())
        registerExtractorAPI(Ahvsh())
        registerExtractorAPI(Guccihide())
        registerExtractorAPI(GDFlix())

    }
}
