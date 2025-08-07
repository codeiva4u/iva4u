
// Domain rotation for better reliability
object DomainRotator {
    private val domainBackups = mapOf(
        "filemoon.to" to listOf("filemoon.in", "filemoon.in"),
        "vidcloud.icu" to listOf("vidcloud.icu"),
        "new6.gdflix.dad" to listOf("new6.gdflix.dad"),
        "luluvdo.com" to listOf("lulu.st"),
        "smoothpre.com" to listOf("filelions.live", "dhcplay.com")
    )
    
    fun getWorkingDomain(primaryDomain: String): String {
        // In a real implementation, this would test domains
        return domainBackups[primaryDomain]?.firstOrNull() ?: primaryDomain
    }
}
