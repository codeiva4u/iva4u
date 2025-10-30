version = 5

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "High Quality Movies and TV Shows"
     authors = listOf("megix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/codeiva4u/iva4u/master/MoviesDrive/icon.png"
}
