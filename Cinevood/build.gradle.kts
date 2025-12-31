// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Cinevood - Hindi Movies & Web Series Provider"
    language = "hi"
    authors = listOf("codeiva4u")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )
    iconUrl = "https://1cinevood.fyi/wp-content/uploads/2020/07/favicon2.png"

    isCrossPlatform = true
}
