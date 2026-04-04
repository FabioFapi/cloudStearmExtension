package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

open class YouTubeProvider(language: String, private val sharedPrefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "YouTube"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override var lang = language
    open val SEARCH_CONTENT_FILTER = "videos"
    override var sequentialMainPage = true

    val service: YoutubeService = ServiceList.YouTube


    companion object {
        const val MAIN_URL = "https://www.youtube.com"
        var SEARCH_PAGE: Page? = null
        lateinit var SEARCH_HANDLER: SearchQueryHandler
    }

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val kiosks = service.kioskList
        val trendingsUrl = kiosks.defaultKioskExtractor.url
        val infoItem = KioskInfo.getInfo(service, trendingsUrl)

        val videos = if (page == 1) {
            infoItem.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = infoItem.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = infoItem.nextPage
            while (count < page && hasNext) {
                val more = KioskInfo.getMoreItems(service, trendingsUrl, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.filter { !it.isShortFormContent }.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = "Trending",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val videos = if (page == 1) {
            playlistInfo.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = playlistInfo.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (count < page && hasNext) {
                val more = PlaylistInfo.getMoreItems(service, url, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = "${playlistInfo.uploaderName}: ${playlistInfo.name}",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        val channelInfo = ChannelInfo.getInfo(url)
        val tabsLinkHandlers = channelInfo.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(service, it) }
        val videoTab = tabs.first { it.name == "videos" }

        val videos = if (page == 1) {
            videoTab.relatedItems.toMutableList()
        } else {
            mutableListOf<InfoItem>()
        }

        if (page > 1) {
            var hasNext = videoTab.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = videoTab.nextPage
            while (count < page && hasNext) {

                val videoTabHandler = tabsLinkHandlers.first { it.url.endsWith("/videos") }
                val more = ChannelTabInfo.getMoreItems(service, videoTabHandler, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = channelInfo.name,
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTrendingEnabled = sharedPrefs?.getBoolean("trending", true) ?: true
        val sections = mutableListOf<HomePageList>()
        if (isTrendingEnabled) {
            val videos = getTrendingVideoUrls(page)
            videos?.let { sections.add(it) }
        }
        val playlistsData = sharedPrefs?.getStringSet("playlists", emptySet()) ?: emptySet()
        if (playlistsData.isNotEmpty()) {
            val triples = playlistsData.map { parseJson<Triple<String, String, Long>>(it) }
            val list = triples.amap { data ->
                val playlistUrl = data.first
                val urlPath = playlistUrl.substringAfter("youtu").substringAfter("/")
                val isPlaylist = urlPath.startsWith("playlist?list=")
                val isChannel = urlPath.startsWith("@") || urlPath.startsWith("channel")
                val customSections = if (isPlaylist && !isChannel) {
                    playlistToSearchResponseList(playlistUrl, page)
                } else if (!isPlaylist && isChannel) {
                    channelToSearchResponseList(playlistUrl, page)
                } else {
                    null
                }
                customSections to data.third
            }
            list.sortedBy { it.second }.forEach {
                val homepageSection = it.first
                if (homepageSection != null) {
                    sections.add(homepageSection)
                }
            }

        }
        if (sections.isEmpty()) {
            sections.add(
                HomePageList(
                    "All sections are disabled. Go to the settings to enable them",
                    emptyList()
                )
            )
        }
        return newHomePageResponse(
            sections, true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val handlerFactory = service.searchQHFactory
        val pageResults = mutableListOf<InfoItem>()
        var hasNextPage = true
        if(page<2){
            SEARCH_HANDLER = handlerFactory.fromQuery(
                query,
                listOf(SEARCH_CONTENT_FILTER),
                null
            )
            val searchInfo = SearchInfo.getInfo(service, SearchQueryHandler(SEARCH_HANDLER))
            pageResults.addAll(searchInfo.relatedItems.toMutableList())
            SEARCH_PAGE = searchInfo.nextPage
            hasNextPage = searchInfo.hasNextPage()
        }
        if (page > 1) {
            val more = SearchInfo.getMoreItems(service, SEARCH_HANDLER, SEARCH_PAGE)
            pageResults.addAll(more.items)
            hasNextPage = more.hasNextPage()
            SEARCH_PAGE = more.nextPage
        }

        val finalResults = pageResults.mapNotNull {
            when (it.infoType) {
                InfoType.PLAYLIST, InfoType.CHANNEL -> {
                    newTvSeriesSearchResponse(it.name, it.url, TvType.Others) {
                        this.posterUrl = it.thumbnails.last().url
                    }
                }

                InfoType.STREAM -> {
                    newMovieSearchResponse(it.name, it.url, TvType.Others) {
                        this.posterUrl = it.thumbnails.last().url
                    }
                }

                else -> {
//                    Log.d("YouTubeProvider", "Other type: ${it.name} \t|\t type: ${it.infoType}")
                    null
                }
            }
        }
        return newSearchResponseList(finalResults, hasNextPage)
    }

    protected fun formatThousands(bigNumber: Long): String{
        val number = bigNumber.toString().reversed()
        val n = number.chunked(3).joinToString(" ").reversed()
        return n

    }

    override suspend fun load(url: String): LoadResponse {
        val isChannel = url.contains("/@") || url.contains("/channel/") || url.contains("/c/")
        val isPlaylist = url.contains("/playlist?")

        if (isChannel) {
            val channelInfo = ChannelInfo.getInfo(url)
            val tabsLinkHandlers = channelInfo.tabs
            val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(service, it) }
            val videoTab = tabs.firstOrNull { it.name == "videos" }
            val videos: List<Episode> = videoTab?.relatedItems?.mapNotNull {
                newEpisode(it.url) {
                    this.name = it.name
                    this.posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }?.reversed() ?: emptyList()
            val avatarUrl = try { channelInfo.avatars.last().url } catch (_: Exception) { null }
            val bannerUrl = try { channelInfo.banners.last().url } catch (_: Exception) { null }
            return newTvSeriesLoadResponse(channelInfo.name, url, TvType.Others, videos) {
                this.posterUrl = avatarUrl
                this.backgroundPosterUrl = bannerUrl
                this.plot = channelInfo.description
                this.tags = listOf("Subscribers: ${formatThousands(channelInfo.subscriberCount)}")
            }
        }

        if (isPlaylist) {
            val playlistInfo = PlaylistInfo.getInfo(url)
            val banner = if (playlistInfo.banners.isNotEmpty()) playlistInfo.banners.last().url
                         else playlistInfo.thumbnails.lastOrNull()?.url
            val eps = playlistInfo.relatedItems.toMutableList()
            var hasNext = playlistInfo.hasNextPage()
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (hasNext && count < 10) {
                val more = PlaylistInfo.getMoreItems(service, url, nextPage)
                eps.addAll(more.items)
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
            val episodes = eps.map { video ->
                newEpisode(video.url) {
                    this.name = video.name
                    this.posterUrl = video.thumbnails.lastOrNull()?.url
                    this.runTime = (video.duration / 60).toInt()
                }
            }
            return newTvSeriesLoadResponse(playlistInfo.name, url, TvType.Others, episodes) {
                this.posterUrl = playlistInfo.thumbnails.lastOrNull()?.url
                this.backgroundPosterUrl = banner
                this.plot = playlistInfo.description.content
                this.actors = listOf(ActorData(
                    Actor(playlistInfo.uploaderName, playlistInfo.uploaderAvatars.lastOrNull()?.url)
                ))
            }
        }

        // Default: treat as video
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()
        val videoInfo = StreamInfo.getInfo(extractor)
        val views = "👀: ${formatThousands(videoInfo.viewCount)}"
        val likes = "👍: ${formatThousands(videoInfo.likeCount)}"
        val length = videoInfo.duration / 60
        return newMovieLoadResponse(videoInfo.name, url, TvType.Others, url) {
            this.posterUrl = videoInfo.thumbnails.lastOrNull()?.url
            this.plot = videoInfo.description.content
            this.duration = length.toInt()
            this.tags = listOf(views, likes)
            this.actors = listOf(ActorData(
                Actor(videoInfo.uploaderName, videoInfo.uploaderAvatars.lastOrNull()?.url)))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val extractor = service.getStreamExtractor(data)
        extractor.fetchPage()
        val videoInfo = StreamInfo.getInfo(extractor)

        var found = false

        // Progressive streams (video + audio combined)
        for (stream in videoInfo.videoStreams) {
            val quality = stream.resolution.replace("p", "").toIntOrNull() ?: -1
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${stream.resolution}",
                    url = stream.content,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = MAIN_URL
                    this.quality = quality
                }
            )
            found = true
        }

        // HLS (live streams)
        if (videoInfo.hlsUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Live",
                    url = videoInfo.hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = MAIN_URL
                    this.quality = -1
                }
            )
            found = true
        }

        return found
    }
}