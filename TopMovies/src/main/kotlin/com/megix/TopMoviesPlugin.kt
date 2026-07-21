package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Moviesmod: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopmoviesProvider())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(LeechPro())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())
    }
}
