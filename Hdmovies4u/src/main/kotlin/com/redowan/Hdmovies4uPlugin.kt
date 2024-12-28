package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@Suppress("unused")
@CloudstreamPlugin
class Hdmovies4uPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hdmovies4u())
        registerExtractorAPI(wishonly())
//        registerExtractorAPI(FsLFastDl())
//        registerExtractorAPI(PixelDrain())
//        registerExtractorAPI(Technorozen())
        registerExtractorAPI(HubCloud())
    }
}