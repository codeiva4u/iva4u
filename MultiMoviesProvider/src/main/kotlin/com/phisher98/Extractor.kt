package com.phisher98

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Note: These extractors are registered for domain recognition
// Actual extraction is handled by CloudStream3's built-in extractors via loadExtractor()

// StreamWish/StreamHG Extractor - multimoviesshg.com, streamwish.com
open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = false
}

// VidHide/EarnVids Extractor - vidhide.com, smoothpre.com  
open class VidHideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = false
}

// StreamP2P Extractor - multimovies.p2pplay.pro
open class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = false
}

// RpmShare Extractor - multimovies.rpmhub.site
open class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = false
}

// UpnShare Extractor - server1.uns.bio
open class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = false
}
