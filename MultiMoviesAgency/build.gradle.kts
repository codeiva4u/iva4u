// use an integer for version numbers
version = 1


cloudstream {
    //description = "Movie website for MultiMovies"
    authors = listOf("Kilo Code")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "hi"
    iconUrl = "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://multimovies.agency/&size=64"

    isCrossPlatform = true
}