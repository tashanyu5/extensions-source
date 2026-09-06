package eu.kanade.tachiyomi.extension.ja.mangatoshokanz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyPair

@Source
abstract class MangaToshokanZ : HttpSource() {
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addNetworkInterceptor(::r18Interceptor)
        .addInterceptor(::fallbackInterceptor)
        .addInterceptor(::imageIntercept)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")

    private var isR18 = false

    private fun r18Interceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host == "r18.mangaz.com" && !isR18) {
            val url = "https://r18.mangaz.com/attention/r18/yes"
            val r18Request = Request.Builder()
                .url(url)
                .headers(headers)
                .head()
                .build()

            isR18 = true
            runCatching {
                client.newCall(r18Request).execute().close()
            }
        }

        return chain.proceed(request)
    }

    private fun fallbackInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 500 && request.url.encodedPath.contains("/series/detail/")) {
            response.close()
            val fallbackUrl = request.url.newBuilder()
                .encodedPath(request.url.encodedPath.replace("/series/detail/", "/book/detail/"))
                .build()
            return chain.proceed(request.newBuilder().url(fallbackUrl).build())
        }

        return response
    }

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()

        if (!url.contains("#scramble_")) return response
        val fragment = request.url.fragment ?: return response
        if (!fragment.startsWith("scramble_")) return response

        val cropsJsonStr = String(Base64.decode(fragment.substringAfter("scramble_"), Base64.URL_SAFE))
        val scrambleObj = JSONObject(cropsJsonStr)
        val targetW = scrambleObj.getInt("w")
        val targetH = scrambleObj.getInt("h")
        val cropsArray = scrambleObj.getJSONArray("crops")

        val srcStream = response.body.byteStream()
        val srcBitmap = BitmapFactory.decodeStream(srcStream) ?: return response
        response.close()

        val resultBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        for (i in 0 until cropsArray.length()) {
            val crop = cropsArray.getJSONObject(i)
            val destX = crop.getInt("x")
            val destY = crop.getInt("y")
            val srcX = crop.getInt("x2")
            val srcY = crop.getInt("y2")
            val w = crop.getInt("w")
            val h = crop.getInt("h")

            // 关键修正：x2/y2 才是下载下来的乱序长条图里的坐标（源），
            // x/y 才是最终页面画布上的坐标（目标）——跟之前两版刚好相反
            val srcRect = Rect(srcX, srcY, srcX + w, srcY + h)
            val destRect = Rect(destX, destY, destX + w, destY + h)
            canvas.drawBitmap(srcBitmap, srcRect, destRect, null)
        }

        val output = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        val responseBody = output.toByteArray().toResponseBody("image/jpeg".toMediaType())

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/ranking/views", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.toMangas(".itemList")
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val header = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("title/addpage_renewal")
            .addQueryParameter("type", "official")
            .addQueryParameter("sort", "new")
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, header)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = response.toMangas("body")
        return MangasPage(mangas, mangas.size == 50)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val header = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("title/addpage_renewal")
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is Category -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("category", categories[filter.state].lowercase())
                    }
                    if (filter.state == 5) {
                        url.host("r18.mangaz.com")
                    }
                }
                is Sort -> {
                    url.addQueryParameter("sort", sortBy[filter.state].lowercase())
                }
                else -> {}
            }
        }

        return GET(url.build(), header)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    private fun Response.toMangas(selector: String): List<SManga> {
        val document = asJsoup()
        val container = document.selectFirst(selector) ?: document.body() ?: return emptyList()

        return container.select("li").filterNot { li ->
            li.selectFirst(".iconConsent") != null
        }.mapNotNull { li ->
            val img = li.selectFirst("img") ?: return@mapNotNull null
            val thumb = img.attr("src").ifBlank {
                img.attr("data-src")
            }

            val titleA = li.selectFirst(".listBoxDetail h4 a")
                ?: li.selectFirst("h4 a")
                ?: li.selectFirst(".listBoxDetail a")

            val candidateText = titleA?.text()?.trim()
            val mangaTitle = if (!candidateText.isNullOrBlank() && !candidateText.all { it.isDigit() }) {
                candidateText
            } else {
                img.attr("alt").trim().takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            } ?: return@mapNotNull null

            val href = titleA?.attr("href")?.ifBlank { null }
                ?: li.selectFirst("a[href*='/detail/']")?.attr("href")
                ?: return@mapNotNull null

            val id = href.substringAfterLast("/").ifBlank { return@mapNotNull null }

            SManga.create().apply {
                url = id
                title = mangaTitle
                thumbnail_url = thumb
            }
        }
    }

    override fun getFilterList() = FilterList(Category(), Sort())

    private class Category : Filter.Select<String>("Category", categories)

    private class Sort : Filter.Select<String>("Sort", sortBy)

    private fun seriesDetailRequest(seriesId: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("series/detail")
            .addPathSegment(seriesId)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = seriesDetailRequest(manga.url)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            document.select(".seriesAuthor li").forEach { li ->
                val label = li.ownText()
                val name = li.select("a").joinToString(", ") { it.text() }
                if (name.isBlank()) return@forEach

                when {
                    label.contains("者") || label.contains("原作") -> {
                        author = if (author.isNullOrEmpty()) name else "$author, $name"
                    }
                    label.contains("作画") || label.contains("マンガ") -> {
                        artist = if (artist.isNullOrEmpty()) name else "$artist, $name"
                    }
                    else -> {
                        if (author.isNullOrEmpty()) author = name
                    }
                }
            }

            description = document.selectFirst(".wordbreak")?.text()
            status = SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request = seriesDetailRequest(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        if (response.request.url.pathSegments.firstOrNull() == "book") {
            return listOf(
                SChapter.create().apply {
                    name = document.selectFirst(".GA4_booktitle")?.text()
                        ?: document.selectFirst("h2")?.text()
                        ?: "第1話"
                    url = document.baseUri().removeSuffix("/").substringAfterLast("/")
                    chapter_number = 1f
                    date_upload = 0
                },
            )
        }

        return document.select(".itemList li").reversed().mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
            val chapterTitle = li.selectFirst(".title")?.text()?.ifBlank { null } ?: a.text().trim()

            SChapter.create().apply {
                name = chapterTitle
                url = a.attr("href").removeSuffix("/").substringAfterLast("/")
                chapter_number = i.toFloat()
                date_upload = 0
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val viewerUrl = "https://vw.mangaz.com/virgo/view/${chapter.url}/i:0"
        return GET(viewerUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        var jsonString: String? = null

        val base64Regex = Regex("""([A-Za-z0-9+/]{200,}={0,2})""")
        for (match in base64Regex.findAll(html)) {
            try {
                val decoded = String(Base64.decode(match.value, Base64.DEFAULT))
                if (decoded.contains("\"Orders\"") && decoded.contains("\"Location\"")) {
                    jsonString = decoded
                    break
                }
            } catch (e: Exception) {
            }
        }

        if (jsonString.isNullOrBlank()) {
            throw Exception("无法在阅读器页面中找到打乱的 Base64 数据")
        }

        val data = JSONObject(jsonString)
        val location = data.getJSONObject("Location")
        val base = location.getString("base")
        val scrambleDir = if (location.has("scramble_dir")) location.getString("scramble_dir") + "/" else ""

        val orders = data.getJSONArray("Orders")
        val pages = mutableListOf<Page>()

        for (i in 0 until orders.length()) {
            val order = orders.getJSONObject(i)
            val name = order.getString("name")
            val scramble = if (order.has("scramble")) order.getJSONObject("scramble") else null

            var imageUrl = "$base$scrambleDir$name"

            if (scramble != null) {
                val scrambleB64 = Base64.encodeToString(scramble.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                imageUrl += "#scramble_$scrambleB64"
            }

            pages.add(Page(i, imageUrl = imageUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private val categories = arrayOf(
            "All", "Mens", "Womens", "TL", "BL", "R18",
        )
        private val sortBy = arrayOf(
            "Popular", "New",
        )
    }
}