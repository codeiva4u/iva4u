// Movies4u CloudStream Plugin

version = 1

cloudstream {
    language = "hi"
    description = "Movies4u - Download HD Movies & Web Series"
    authors = listOf("codeiva4u")
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    iconUrl = "https://pub-7552829be8684482812b2b7bbeb7088c.r2.dev/appicon.png"
}

android {
    namespace = "com.movies4u"
}
