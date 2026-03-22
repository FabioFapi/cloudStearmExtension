// use an integer for version numbers
version = 2

cloudstream {
    language = "en"

    description = "Live sports streams from StreamSports99. Covers Soccer, Basketball, Tennis, Motorsport, and more."
    authors = listOf("FabioFapi")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3

    tvTypes = listOf("Live")

    requiresResources = false

    iconUrl = "https://streamsports99.su/favicon.ico"
}
