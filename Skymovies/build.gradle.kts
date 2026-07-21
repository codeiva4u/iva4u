// Skymovies CloudStream Plugin

version = 1

cloudstream {
    language = "hi"
    description = "Skymovies - Download HD Movies & Web Series"
    authors = listOf("codeiva4u")
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    iconUrl = "https://skymovieshd.ceo/images/logo2.png"
}

android {
    namespace = "com.skymovies"
}
