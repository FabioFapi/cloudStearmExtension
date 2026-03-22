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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class StreamSports99 : MainAPI() {
    override var mainUrl = "https://streamsports99.su"
    override var name = "StreamSports99"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true

    // Shared CloudflareKiller instance – bypasses Cloudflare JS challenges on Android
    private val cfKiller = CloudflareKiller()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // Matches /player/soccer-bNwf86oU (sport prefix + 6-12 char alphanumeric id)
        private val PLAYER_URL_REGEX =
            Regex("""/player/([a-zA-Z]+(?:-[a-zA-Z]+)*)-([a-zA-Z0-9]{6,12})(?=[^-a-zA-Z0-9]|$)""")

        // Static list of candidate REST API paths (tried in order)
        private val CANDIDATE_API_PATHS = listOf(
            "/api/events",
            "/api/matches",
            "/api/schedule",
            "/api/live",
            "/api/v1/events",
            "/api/v2/events",
            "/schedule",
            "/events",
        )
    }

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // ── Data classes ──────────────────────────────────────────────────────────

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
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("poster") val poster: String? = null,
    ) {
        fun toPlayerUrl(baseUrl: String): String? {
            url?.let { return if (it.startsWith("http")) it else "$baseUrl$it" }
            link?.let { return if (it.startsWith("http")) it else "$baseUrl$it" }
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

    data class StreamApiResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("hls") val hls: String? = null,
    )

    // ── CloudflareKiller for video streams ────────────────────────────────────

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response =
                cfKiller.intercept(chain)
        }
    }

    // ── API-based event fetching ──────────────────────────────────────────────

    /**
     * Tries all candidate REST API paths. Returns null if none works.
     */
    private suspend fun fetchEventsFromApi(filter: String? = null): List<LiveSearchResponse>? {
        val apiHeaders = baseHeaders + mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to mainUrl,
        )

        // Also try any API paths discovered from the JS bundle
        val dynamicPaths = discoverApiPathsFromBundle()
        val allPaths = (CANDIDATE_API_PATHS + dynamicPaths).distinct()

        for (path in allPaths) {
            try {
                val url = "$mainUrl$path" + (if (filter != null) "?status=$filter" else "")
                val resp = app.get(url, headers = apiHeaders, interceptor = cfKiller)
                if (resp.code != 200) continue
                val ct = resp.headers["content-type"] ?: ""
                if (!ct.contains("json")) continue

                val text = resp.text
                val apiResp = tryParseJson<ApiResponse>(text)
                val events = apiResp?.allEvents()?.takeIf { it.isNotEmpty() }
                    ?: tryParseJson<List<ApiEvent>>(text)?.takeIf { it.isNotEmpty() }
                    ?: continue

                Log.d("StreamSports99", "API $path: ${events.size} events")
                return events.mapNotNull { event ->
                    val playerUrl = event.toPlayerUrl(mainUrl) ?: return@mapNotNull null
                    newLiveSearchResponse(event.toDisplayName(), playerUrl, TvType.Live) {
                        this.posterUrl = event.thumbnail ?: event.poster
                    }
                }
            } catch (e: Exception) {
                Log.d("StreamSports99", "API $path failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Fetches the homepage HTML (bypassing Cloudflare), then scans the main JS
     * bundle for `/api/...` strings to discover unknown API paths dynamically.
     */
    private var cachedApiPaths: List<String>? = null

    private suspend fun discoverApiPathsFromBundle(): List<String> {
        cachedApiPaths?.let { return it }
        return try {
            val doc = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller).document
            // Collect all JS script sources (skip tiny inline scripts)
            val scriptSrcs = doc.select("script[src]").map { it.attr("src") }
                .filter { it.endsWith(".js") && "polyfill" !in it }
                .map { if (it.startsWith("http")) it else "$mainUrl$it" }
                .take(8)

            val foundPaths = mutableListOf<String>()
            val apiPathRegex = Regex("""["'](/api/[a-zA-Z0-9/_-]{2,40})["']""")

            for (src in scriptSrcs) {
                try {
                    val js = app.get(src, headers = baseHeaders, interceptor = cfKiller).text
                    apiPathRegex.findAll(js).forEach { m ->
                        val p = m.groupValues[1]
                        if (!foundPaths.contains(p)) foundPaths.add(p)
                    }
                } catch (_: Exception) {}
            }

            Log.d("StreamSports99", "Discovered API paths from bundle: $foundPaths")
            cachedApiPaths = foundPaths
            foundPaths
        } catch (e: Exception) {
            Log.d("StreamSports99", "Bundle discovery failed: ${e.message}")
            emptyList()
        }
    }

    // ── HTML-based event fetching ─────────────────────────────────────────────

    private fun buildEventName(sport: String, card: Element?): String {
        if (card == null) return sport.replaceFirstChar { it.uppercase() }

        val league = card.select(
            "[class*='league'],[class*='competition'],[class*='category'],[class*='tournament']"
        ).firstOrNull()?.text()?.takeIf { it.isNotBlank() }

        val teamTexts = card.select(
            "[class*='team'],[class*='name'],[class*='home'],[class*='away']"
        ).map { it.text().trim() }.filter { it.length > 2 }

        return buildString {
            append(sport.replaceFirstChar { it.uppercase() })
            if (league != null) append(" | $league")
            if (teamTexts.size >= 2) append(" | ${teamTexts[0]} vs ${teamTexts[1]}")
            else if (teamTexts.isNotEmpty()) append(" | ${teamTexts[0]}")
        }
    }

    private suspend fun fetchEventsFromHtml(): List<LiveSearchResponse> {
        val doc = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller).document
        val events = mutableListOf<LiveSearchResponse>()
        val seen = mutableSetOf<String>()

        fun addFromHref(href: String, contextEl: Element?) {
            val full = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(full)) return
            val m = PLAYER_URL_REGEX.find(full) ?: return
            val sport = m.groupValues[1]
            val card = contextEl?.closest(
                "article,li,[class*='card'],[class*='match'],[class*='event'],[class*='game'],[class*='item']"
            )
            events.add(newLiveSearchResponse(buildEventName(sport, card ?: contextEl), full, TvType.Live))
        }

        // 1. <a href="/player/...">
        doc.select("a[href*='/player/']").forEach { addFromHref(it.attr("href"), it) }

        // 2. data-url / data-href / data-link attributes
        doc.select("[data-url*='/player/'],[data-href*='/player/'],[data-link*='/player/']")
            .forEach { el ->
                val href = el.attr("data-url").ifEmpty { el.attr("data-href") }
                    .ifEmpty { el.attr("data-link") }
                addFromHref(href, el)
            }

        // 3. Raw HTML scan (catches URLs inside inline JS / JSON)
        if (events.isEmpty()) {
            PLAYER_URL_REGEX.findAll(doc.html()).forEach { m ->
                val full = "$mainUrl${m.value}"
                if (seen.add(full)) {
                    events.add(
                        newLiveSearchResponse(
                            m.groupValues[1].replaceFirstChar { it.uppercase() },
                            full,
                            TvType.Live
                        )
                    )
                }
            }
        }

        // 4. Next.js __NEXT_DATA__ / Nuxt __NUXT__ embedded JSON
        if (events.isEmpty()) {
            val embeddedJson = doc.selectFirst("script#__NEXT_DATA__")?.data()
                ?: doc.select("script").firstOrNull { "__NUXT__" in it.data() }?.data()
                    ?.substringAfter("=", "")?.trim()?.trimEnd(';')

            embeddedJson?.let { json ->
                PLAYER_URL_REGEX.findAll(json).forEach { m ->
                    val full = "$mainUrl${m.value}"
                    if (seen.add(full)) {
                        events.add(
                            newLiveSearchResponse(
                                m.groupValues[1].replaceFirstChar { it.uppercase() },
                                full,
                                TvType.Live
                            )
                        )
                    }
                }
            }
        }

        Log.d("StreamSports99", "HTML scrape: ${events.size} events found")
        return events
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl?filter=live" to "Live",
        "$mainUrl?filter=upcoming" to "Upcoming",
        "$mainUrl" to "Today's Events",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val filter = when {
            request.data.contains("filter=live") -> "live"
            request.data.contains("filter=upcoming") -> "upcoming"
            else -> null
        }

        val events: List<LiveSearchResponse> =
            fetchEventsFromApi(filter) ?: fetchEventsFromHtml()

        return newHomePageResponse(
            HomePageList(request.name, events, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val all = fetchEventsFromApi() ?: fetchEventsFromHtml()
        return all.filter { query.lowercase() in it.name.lowercase() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val headers = baseHeaders + mapOf("Referer" to mainUrl)
        val doc = app.get(url, headers = headers, interceptor = cfKiller).document

        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
        val h1 = doc.selectFirst("h1,h2")?.text()
        val pageTitle = doc.selectFirst("title")?.text()
            ?.substringBefore(" - ")?.substringBefore(" | ")?.trim()

        val title = (ogTitle ?: h1 ?: pageTitle ?: url.substringAfterLast("/player/"))
            .trim().ifBlank { url.substringAfterLast("/player/") }

        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val refHeaders = baseHeaders + mapOf("Referer" to mainUrl)
        val doc = app.get(data, headers = refHeaders, interceptor = cfKiller).document
        val pageHtml = doc.html()
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        var found = false

        Log.d("StreamSports99", "loadLinks: $data")

        // 1. Iframes
        doc.select("iframe[src],iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty() && !src.startsWith("javascript") && src != "about:blank") {
                val fullSrc = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http") -> src
                    else -> fixUrl(src)
                }
                Log.d("StreamSports99", "Iframe: $fullSrc")
                try {
                    loadExtractor(fullSrc, data, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    Log.d("StreamSports99", "Iframe extractor error: ${e.message}")
                }
            }
        }

        // 2. HLS .m3u8 URLs anywhere on the page
        Regex("""https?://[^\s'"<>{}\[\]()]+\.m3u8(?:\?[^\s'"<>{}\[\]()]*)?""")
            .findAll(pageHtml).forEach { m ->
                callback(newExtractorLink(name, name, m.value, ExtractorLinkType.M3U8) {
                    quality = 0; referer = data; headers = refHeaders
                })
                found = true
            }

        // 3. DASH .mpd URLs anywhere on the page
        Regex("""https?://[^\s'"<>{}\[\]()]+\.mpd(?:\?[^\s'"<>{}\[\]()]*)?""")
            .findAll(pageHtml).forEach { m ->
                callback(newExtractorLink(name, "$name DASH", m.value, ExtractorLinkType.DASH) {
                    quality = 0; referer = data; headers = refHeaders
                })
                found = true
            }

        // 4. JWPlayer / VideoJS / Plyr config: "file":"...", "src":"..."
        Regex("""["'](?:file|src|source|stream|url)["']\s*:\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(scripts).forEach { m ->
            val u = m.groupValues[1]
            val lt = when {
                u.contains(".m3u8") -> ExtractorLinkType.M3U8
                u.contains(".mpd") -> ExtractorLinkType.DASH
                u.contains("stream", true) || u.contains("live", true) -> ExtractorLinkType.M3U8
                else -> return@forEach
            }
            Log.d("StreamSports99", "Player config: $u")
            callback(newExtractorLink(name, name, u, lt) {
                quality = 0; referer = data; headers = refHeaders
            })
            found = true
        }

        // 5. <source src="...">
        doc.select("source[src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotEmpty() } ?: return@forEach
            val type = source.attr("type")
            val lt = when {
                src.contains(".m3u8") || type.contains("mpegurl", true) -> ExtractorLinkType.M3U8
                src.contains(".mpd") || type.contains("dash", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(newExtractorLink(name, name, fixUrl(src), lt) {
                quality = 0; referer = data; headers = refHeaders
            })
            found = true
        }

        // 6. Fallback: try common stream API patterns using the event ID
        if (!found) {
            val match = PLAYER_URL_REGEX.find(data)
            if (match != null) {
                val sport = match.groupValues[1]
                val id = match.groupValues[2]
                val streamApis = listOf(
                    "$mainUrl/api/stream/$id",
                    "$mainUrl/api/events/$id/stream",
                    "$mainUrl/api/player/$sport-$id",
                    "$mainUrl/stream/$id",
                )
                for (apiUrl in streamApis) {
                    try {
                        val resp = app.get(
                            apiUrl,
                            headers = refHeaders + mapOf("Accept" to "application/json"),
                            interceptor = cfKiller
                        )
                        if (resp.code != 200) continue
                        val sd = tryParseJson<StreamApiResponse>(resp.text) ?: continue
                        val su = sd.url ?: sd.stream ?: sd.src ?: sd.hls ?: sd.file ?: continue
                        val lt = when {
                            su.contains(".m3u8") -> ExtractorLinkType.M3U8
                            su.contains(".mpd") -> ExtractorLinkType.DASH
                            else -> ExtractorLinkType.VIDEO
                        }
                        Log.d("StreamSports99", "Stream API: $su")
                        callback(newExtractorLink(name, name, su, lt) {
                            quality = 0; referer = data; headers = refHeaders
                        })
                        found = true
                        break
                    } catch (e: Exception) {
                        Log.d("StreamSports99", "Stream API ($apiUrl) failed: ${e.message}")
                    }
                }
            }
        }

        return found
    }
}
