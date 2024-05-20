package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.lagradost.cloudstream3.network.CloudflareKiller

open class VegaMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://vegamovies.yt"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    private val cfInterceptor = CloudflareKiller()
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/featured/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney Plus Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/mx-original/page/%d/" to " MX Original",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page), interceptor = cfInterceptor).document
        val home = document.select("article.post-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title")
        val trimTitle = title?.let {
            if (it.contains("Download ")) {
                it.replace("Download ", "")
            } else {
                it
            }
        } ?: ""

        val href = fixUrl(this.selectFirst("a")?.attr("href").toString())
        val noscriptTag = this.selectFirst("noscript")
        var posterUrl = if (noscriptTag != null) {
            fixUrlNull(noscriptTag.selectFirst("img")?.attr("src"))
        } else {
            fixUrlNull(this.selectFirst("img.blog-picture")?.attr("src"))
        }

        if(posterUrl == null) {
            val document = app.get(href).document
            posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        }

        return newMovieSearchResponse(trimTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..2) {
            val document = app.get("$mainUrl/page/$i/?s=$query", interceptor = cfInterceptor).document

            val results = document.select("article.post-item").mapNotNull { it.toSearchResult() }

            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfInterceptor).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
        val trimTitle = title?.let {
            if (it.contains("Download ")) {
                it.replace("Download ", "")
            } else {
                it
            }
        } ?: ""
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val documentText = document.text()

        val tvType = if (url.contains("season") ||
                  (title?.contains("(Season") ?: false) ||
                  Regex("Series synopsis").containsMatchIn(documentText) || Regex("Series Name").containsMatchIn(documentText)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        if (tvType == TvType.TvSeries) {
            val div = document.select("div.entry-content")
            val hTags = div.select("h3:matches((?i)(480p|720p|1080p|2160p|4K)),h5:matches((?i)(480p|720p|1080p|2160p|4K))")
                .filter { element -> !element.text().contains("Download", true) }
            val tvSeriesEpisodes = mutableListOf<Episode>()
            var seasonNum = 1

            for(tag in hTags) {
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason = realSeasonRegex.find(tag.toString())?.groupValues?.get(1) ?: "Unknown"
                val qualityRegex = Regex("""(1080p|720p|480p|2160p|4K|[0-9]*0p)""")
                val quality = qualityRegex.find(tag.toString())?.groupValues?.get(1) ?: "Unknown"

                val unilinks = tag.nextElementSibling()?.toString()

                var Eurl = ""

                val regexList = listOf (
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*V-Cloud)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*Episode Link)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*Episodes Link)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*Single Episode)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*Download)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/(?=.*Download Now)(?!.*G-Direct)"""),
                    Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/""")
                )

                if (unilinks != null) {
                    for (regex in regexList) {
                        val match = regex.find(unilinks)
                        if (match != null) {
                            Eurl = match.value
                            break
                        }
                    }
                }

                if(Eurl.isBlank()) {
                    val newlinks = tag.toString()
                    for (regex in regexList) {
                        val match = regex.find(newlinks)
                        if (match != null) {
                            Eurl = match.value
                            break
                        }
                    }
                }

                if (Eurl.isNotBlank()) {
                    val document2 = app.get(Eurl).document
                    val vcloudRegex1 = Regex("""https:\/\/vcloud\.lol\/[^\s"]+""")
                    val vcloudRegex2 = Regex("""https:\/\/vcloud\.lol\/\w+""")
                    val fastDlRegex = Regex("""https:\/\/fastdl\.icu\/embed\?download=[a-zA-Z0-9]+""")

                    var vcloudLinks = vcloudRegex1.findAll(document2.html()).mapNotNull { it.value }.toList()
                    if (vcloudLinks.isEmpty()) {
                        vcloudLinks = vcloudRegex2.findAll(document2.html()).mapNotNull { it.value }.toList()
                        if (vcloudLinks.isEmpty()) {
                            vcloudLinks = fastDlRegex.findAll(document2.html()).mapNotNull { it.value }.toList()
                        }
                    }
                    val episodes = vcloudLinks.mapNotNull { vcloudlink ->
                        Episode(
                            name = "S${realSeason} E${vcloudLinks.indexOf(vcloudlink) + 1} ${quality}",
                            data = vcloudlink,
                            season = seasonNum,
                            episode = vcloudLinks.indexOf(vcloudlink) + 1,
                        )
                    }

                    tvSeriesEpisodes.addAll(episodes)
                    seasonNum++
                }
            }
            return newTvSeriesLoadResponse(trimTitle, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
            }
        }
        else {
            return newMovieLoadResponse(trimTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("vcloud.lol") || data.contains("fastdl")) {
            loadExtractor(data, subtitleCallback, callback)
            return true
        } else {
            val document1 = app.get(data).document
            val regex = Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/""")
            val links = regex.findAll(document1.html()).mapNotNull { it.value }.toList()

            links.mapNotNull { link ->
                val document2 = app.get(link).document
                val vcloudRegex = Regex("""https:\/\/vcloud\.lol\/[^\s"]+""")
                var vcloudLinks = vcloudRegex.findAll(document2.html()).mapNotNull { it.value }.toList()
                if(vcloudLinks.isEmpty()) {
                    val newRegex = Regex("""https:\/\/fastdl\.icu\/embed\?download=[a-zA-Z0-9]+""")
                    vcloudLinks = newRegex.findAll(document2.html()).mapNotNull { it.value }.toList()
                }

                if (vcloudLinks.isNotEmpty()) {
                    loadExtractor(vcloudLinks.first(), subtitleCallback, callback)
                }
            }
            return true
        }
    }
}
