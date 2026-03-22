package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class StreamSports99 : MainAPI() {
    override var mainUrl = "https://streamsports99.su"
    override var name = "StreamSports99"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Matches /player/soccer-bNwf86oU style URLs
        private val PLAYER_URL_REGEX = Regex("""/player/([a-zA-Z]+(?:-[a-zA-Z]+)*)-([a-zA-Z0-9]{6,12})(?=[^-a-zA-Z0-9]|$)""")

        // Potential API endpoints to try
        private val API_ENDPOINTS = listOf(
            "/api/events",
            "/api/matches",
            "/api/schedule",
            "/api/live",
            "/api/v1/events",
        )
    }

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // Data classes for JSON API responses (covers common patterns)
    data class ApiEvent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sport") val sport: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("league") val league: String? = null,
        @JsonProperty("competition") val competition: String? = null,
        @JsonProperty("home") val home: String? = null,
        @JsonProperty("away") val away: String? = null,
        @JsonProperty("team1") val team1: String? = null,
        @JsonProperty("team2") val team2: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("poster") val poster: String? = null,
    ) {
        fun toPlayerUrl(baseUrl: String): String? {
            url?.let { if (it.startsWith("http")) return it else return "$baseUrl$it" }
            link?.let { if (it.startsWith("http")) return it else return "$baseUrl$it" }
            slug?.let { return "$baseUrl/player/$it" }
            val sportSlug = (sport ?: category)?.lowercase()?.replace(" ", "-")
            id?.let { if (sportSlug != null) return "$baseUrl/player/$sportSlug-$it" }
            return null
        }

        fun toDisplayName(): String {
            title?.let { return it }
            val t1 = home ?: team1
            val t2 = away ?: team2
            val sportName = (sport ?: category)?.replaceFirstChar { it.uppercase() } ?: "Live"
            val leagueName = league ?: competition
            return buildString {
                append(sportName)
                if (leagueName != null) append(" | $leagueName")
                if (t1 != null && t2 != null) append(" | $t1 vs $t2")
            }
        }
    }

    data class ApiResponse(
        @JsonProperty("data") val data: List<ApiEvent>? = null,
        @JsonProperty("events") val events: List<ApiEvent>? = null,
        @JsonProperty("matches") val matches: List<ApiEvent>? = null,
        @JsonProperty("results") val results: List<ApiEvent>? = null,
    ) {
        fun allEvents(): List<ApiEvent> =
            data ?: events ?: matches ?: results ?: emptyList()
    }

    // --- API-based fetching ---

    private suspend fun fetchEventsFromApi(filter: String? = null): List<LiveSearchResponse>? {
        for (endpoint in API_ENDPOINTS) {
            try {
                val url = if (filter != null) "$mainUrl$endpoint?status=$filter" else "$mainUrl$endpoint"
                val resp = app.get(
                    url,
                    headers = baseHeaders + mapOf(
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to mainUrl,
                    )
                )
                if (resp.code != 200) continue
                val ct = resp.headers["content-type"] ?: ""
                if (!ct.contains("json")) continue

                val text = resp.text
                // Try as wrapped response first, then as plain list
                val apiResp = tryParseJson<ApiResponse>(text)
                val events = apiResp?.allEvents()?.takeIf { it.isNotEmpty() }
                    ?: tryParseJson<List<ApiEvent>>(text)?.takeIf { it.isNotEmpty() }
                    ?: continue

                Log.d("StreamSports99", "API $endpoint returned ${events.size} events")
                return events.mapNotNull { event ->
                    val playerUrl = event.toPlayerUrl(mainUrl) ?: return@mapNotNull null
                    newLiveSearchResponse(event.toDisplayName(), playerUrl, TvType.Live) {
                        this.posterUrl = event.thumbnail ?: event.poster
                    }
                }
            } catch (e: Exception) {
                Log.d("StreamSports99", "API endpoint failed ($endpoint): ${e.message}")
            }
        }
        return null
    }

    // --- HTML-based fetching ---

    private fun buildEventName(sport: String, card: Element?): String {
        if (card == null) return sport.replaceFirstChar { it.uppercase() }

        // Try to find league/competition name
        val league = card.select(
            "[class*='league'],[class*='competition'],[class*='category'],[class*='tournament']"
        ).firstOrNull()?.text()?.takeIf { it.isNotBlank() }

        // Try to find team names (skip very short or badge-like texts)
        val teamTexts = card.select(
            "[class*='team'],[class*='name'],[class*='home'],[class*='away']"
        ).map { it.text().trim() }.filter { it.length > 2 }

        val sportTitle = sport.replaceFirstChar { it.uppercase() }

        return buildString {
            append(sportTitle)
            if (league != null) append(" | $league")
            if (teamTexts.size >= 2) {
                append(" | ${teamTexts[0]} vs ${teamTexts[1]}")
            } else if (teamTexts.isNotEmpty()) {
                append(" | ${teamTexts[0]}")
            }
        }
    }

    private suspend fun fetchEventsFromHtml(): List<LiveSearchResponse> {
        val doc = app.get(mainUrl, headers = baseHeaders).document
        val events = mutableListOf<LiveSearchResponse>()
        val seen = mutableSetOf<String>()

        // Strategy 1: direct <a href="/player/..."> links
        doc.select("a[href*='/player/']").forEach { anchor ->
            val href = anchor.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            if (seen.add(href)) {
                val match = PLAYER_URL_REGEX.find(href) ?: return@forEach
                val sport = match.groupValues[1]
                val card = anchor.closest(
                    "article,li,[class*='card'],[class*='match'],[class*='event'],[class*='game'],[class*='item']"
                )
                val displayName = buildEventName(sport, card ?: anchor.parent())
                events.add(newLiveSearchResponse(displayName, href, TvType.Live))
            }
        }

        // Strategy 2: data-* attributes containing player URLs
        if (events.isEmpty()) {
            doc.select("[data-url*='/player/'],[data-href*='/player/'],[data-link*='/player/']")
                .forEach { el ->
                    val href = (el.attr("data-url").ifEmpty { el.attr("data-href") }
                        .ifEmpty { el.attr("data-link") }).let {
                        if (it.startsWith("http")) it else "$mainUrl$it"
                    }
                    if (seen.add(href)) {
                        val match = PLAYER_URL_REGEX.find(href) ?: return@forEach
                        val sport = match.groupValues[1]
                        val card = el.closest(
                            "article,li,[class*='card'],[class*='match'],[class*='event'],[class*='game']"
                        )
                        val displayName = buildEventName(sport, card ?: el.parent())
                        events.add(newLiveSearchResponse(displayName, href, TvType.Live))
                    }
                }
        }

        // Strategy 3: scan raw HTML for player URLs (catches JS-embedded links)
        if (events.isEmpty()) {
            PLAYER_URL_REGEX.findAll(doc.html()).forEach { match ->
                val playerPath = match.value
                val href = "$mainUrl$playerPath"
                if (seen.add(href)) {
                    val sport = match.groupValues[1]
                    events.add(
                        newLiveSearchResponse(
                            sport.replaceFirstChar { it.uppercase() },
                            href,
                            TvType.Live
                        )
                    )
                }
            }
        }

        // Strategy 4: try Next.js __NEXT_DATA__ or Nuxt __NUXT__ embedded JSON
        if (events.isEmpty()) {
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
                ?: doc.select("script").firstOrNull { it.data().contains("__NUXT__") }?.data()
                    ?.substringAfter("=", "")?.trim()?.trimEnd(';')

            if (nextData != null) {
                PLAYER_URL_REGEX.findAll(nextData).forEach { match ->
                    val href = "$mainUrl${match.value}"
                    if (seen.add(href)) {
                        val sport = match.groupValues[1]
                        events.add(
                            newLiveSearchResponse(
                                sport.replaceFirstChar { it.uppercase() },
                                href,
                                TvType.Live
                            )
                        )
                    }
                }
            }
        }

        Log.d("StreamSports99", "HTML scrape: found ${events.size} events")
        return events
    }

    // ---- Main page ----

    override val mainPage = mainPageOf(
        "$mainUrl?filter=live" to "Live",
        "$mainUrl?filter=upcoming" to "Upcoming",
        "$mainUrl" to "Today's Events",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Determine filter from request data URL
        val filter = when {
            request.data.contains("filter=live") -> "live"
            request.data.contains("filter=upcoming") -> "upcoming"
            else -> null
        }

        val events: List<LiveSearchResponse> =
            fetchEventsFromApi(filter)
                ?: fetchEventsFromHtml()

        return newHomePageResponse(
            HomePageList(request.name, events, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ---- Search ----

    override suspend fun search(query: String): List<SearchResponse> {
        val allEvents: List<LiveSearchResponse> =
            fetchEventsFromApi() ?: fetchEventsFromHtml()
        return allEvents.filter {
            query.lowercase() in it.name.lowercase()
        }
    }

    // ---- Load ----

    override suspend fun load(url: String): LoadResponse {
        val headers = baseHeaders + mapOf("Referer" to mainUrl)
        val doc = app.get(url, headers = headers).document

        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
        val h1 = doc.selectFirst("h1,h2")?.text()
        val pageTitle = doc.selectFirst("title")?.text()
            ?.substringBefore(" - ")?.substringBefore(" | ")?.trim()

        val title = (ogTitle ?: h1 ?: pageTitle
            ?: url.substringAfterLast("/player/")).trim()
            .ifBlank { url.substringAfterLast("/player/") }

        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // ---- Load links ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val refHeaders = baseHeaders + mapOf("Referer" to mainUrl)
        val doc = app.get(data, headers = refHeaders).document
        val pageHtml = doc.html()
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        var found = false

        Log.d("StreamSports99", "loadLinks: $data")

        // 1. Iframes (most common embed pattern for streaming sites)
        doc.select("iframe[src],iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty() && !src.startsWith("javascript") && src != "about:blank") {
                val fullSrc = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http") -> src
                    else -> fixUrl(src)
                }
                Log.d("StreamSports99", "Iframe found: $fullSrc")
                try {
                    loadExtractor(fullSrc, data, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    Log.d("StreamSports99", "Iframe extractor error: ${e.message}")
                }
            }
        }

        // 2. HLS (.m3u8) streams anywhere in page
        Regex("""https?://[^\s'"<>{}\[\]()]+\.m3u8(?:\?[^\s'"<>{}\[\]()]*)?""")
            .findAll(pageHtml).forEach { m ->
                val u = m.value.trim('"', '\'')
                Log.d("StreamSports99", "HLS: $u")
                callback(
                    newExtractorLink(name, name, u, ExtractorLinkType.M3U8) {
                        quality = 0; referer = data; headers = refHeaders
                    }
                )
                found = true
            }

        // 3. DASH (.mpd) streams anywhere in page
        Regex("""https?://[^\s'"<>{}\[\]()]+\.mpd(?:\?[^\s'"<>{}\[\]()]*)?""")
            .findAll(pageHtml).forEach { m ->
                val u = m.value.trim('"', '\'')
                Log.d("StreamSports99", "DASH: $u")
                callback(
                    newExtractorLink(name, "$name DASH", u, ExtractorLinkType.DASH) {
                        quality = 0; referer = data; headers = refHeaders
                    }
                )
                found = true
            }

        // 4. JWPlayer / VideoJS / Plyr config in JavaScript
        // Patterns: file:"...", src:"...", source:"..."
        Regex("""["'](?:file|src|source|stream|url)["']\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(scripts).forEach { m ->
                val u = m.groupValues[1]
                val linkType = when {
                    u.contains(".m3u8") -> ExtractorLinkType.M3U8
                    u.contains(".mpd") -> ExtractorLinkType.DASH
                    u.contains("stream", ignoreCase = true) || u.contains("live", ignoreCase = true) ->
                        ExtractorLinkType.M3U8
                    else -> return@forEach
                }
                Log.d("StreamSports99", "Player config stream: $u")
                callback(
                    newExtractorLink(name, name, u, linkType) {
                        quality = 0; referer = data; headers = refHeaders
                    }
                )
                found = true
            }

        // 5. <source> tags (HTML5 video)
        doc.select("source[src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotEmpty() } ?: return@forEach
            val type = source.attr("type")
            val linkType = when {
                src.contains(".m3u8") || type.contains("mpegurl", true) -> ExtractorLinkType.M3U8
                src.contains(".mpd") || type.contains("dash", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            Log.d("StreamSports99", "<source>: $src")
            callback(
                newExtractorLink(name, name, fixUrl(src), linkType) {
                    quality = 0; referer = data; headers = refHeaders
                }
            )
            found = true
        }

        // 6. Try API endpoint for stream URL using the event ID from URL
        // e.g. /player/soccer-bNwf86oU -> id = bNwf86oU, sport = soccer
        if (!found) {
            val match = PLAYER_URL_REGEX.find(data)
            if (match != null) {
                val sport = match.groupValues[1]
                val id = match.groupValues[2]
                val streamApiUrls = listOf(
                    "$mainUrl/api/stream/$id",
                    "$mainUrl/api/events/$id/stream",
                    "$mainUrl/api/player/$sport-$id",
                    "$mainUrl/stream/$id",
                )
                for (apiUrl in streamApiUrls) {
                    try {
                        val resp = app.get(
                            apiUrl,
                            headers = refHeaders + mapOf("Accept" to "application/json")
                        )
                        if (resp.code != 200) continue
                        val streamData = tryParseJson<StreamApiResponse>(resp.text) ?: continue
                        val streamUrl = streamData.url ?: streamData.stream ?: streamData.src ?: continue
                        val linkType = when {
                            streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
                            else -> ExtractorLinkType.VIDEO
                        }
                        Log.d("StreamSports99", "Stream API: $streamUrl")
                        callback(
                            newExtractorLink(name, name, streamUrl, linkType) {
                                quality = 0; referer = data; headers = refHeaders
                            }
                        )
                        found = true
                        break
                    } catch (e: Exception) {
                        Log.d("StreamSports99", "Stream API failed ($apiUrl): ${e.message}")
                    }
                }
            }
        }

        return found
    }

    data class StreamApiResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("hls") val hls: String? = null,
    )
}
