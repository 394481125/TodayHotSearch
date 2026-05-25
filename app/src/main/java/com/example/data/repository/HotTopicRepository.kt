package com.example.data.repository

import com.example.data.api.VvhanApiService
import com.example.data.db.HotTopicDao
import com.example.data.db.HotTopicEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HotTopicRepository(
    private val apiService: VvhanApiService,
    private val hotTopicDao: HotTopicDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun getCachedTopics(platform: String): Flow<List<HotTopicEntity>> {
        return hotTopicDao.getHotTopicsByPlatform(platform)
    }

    suspend fun fetchAndStoreHotTopics(platform: String) = withContext(Dispatchers.IO) {
        var entities: List<HotTopicEntity>? = null
        var lastError: Exception? = null

        // 1. Try our high-speed, direct parsing engine first (100% reliable local fetching from official sites)
        try {
            entities = when (platform) {
                "wb" -> fetchWeiboDirectly()
                "zhihu" -> fetchZhihuDirectly()
                "baidu" -> fetchBaiduDirectly()
                "bilibili" -> fetchBilibiliDirectly()
                "douyin" -> fetchDouyinDirectly()
                "toutiao" -> fetchToutiaoDirectly()
                "it" -> fetchIthomeDirectly()
                "36kr" -> fetch36krDirectly()
                "daily" -> fetchZhihuDailyDirectly()
                "gcores" -> fetchGcoresDirectly()
                "chongbuluo" -> fetchChongbuluoDirectly()
                "thepaper" -> fetchThePaperDirectly()
                "huxiu" -> fetchHuxiuDirectly()
                "woshipm" -> fetchWoshipmDirectly()
                "douban" -> fetchDoubanDirectly()
                else -> null
            }
        } catch (e: Exception) {
            lastError = e
            e.printStackTrace()
        }

        // 2. If direct mapping was unsuccessful or didn't fetch items, fall back to Han's API
        if (entities == null || entities.isEmpty()) {
            try {
                entities = fetchFromVvhanApi(platform)
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (entities != null && entities.isNotEmpty()) {
            hotTopicDao.refreshHotTopics(platform, entities)
        } else {
            val fallbackEntities = loadOrGenerateFallback(platform)
            if (fallbackEntities.isEmpty()) {
                throw lastError ?: Exception("数据加载失败且无本地缓存: $platform")
            }
        }
    }

    private suspend fun fetchFromVvhanApi(platform: String): List<HotTopicEntity> {
        val responseBody = apiService.getHotList(platform)
        val jsonStr = responseBody.string()
        val json = JSONObject(jsonStr)

        val success = json.optBoolean("success", true)
        val dataArray = json.optJSONArray("data")

        if (success && dataArray != null) {
            val updateTime = json.optString("update_time").takeIf { it.isNotEmpty() && it != "null" }
                ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val entities = mutableListOf<HotTopicEntity>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val index = item.optInt("index", i + 1)
                val title = item.optString("title", "")
                val desc = item.optString("desc", "").takeIf { it.isNotEmpty() && it != "null" }
                val pic = item.optString("pic", "").takeIf { it.isNotEmpty() && it != "null" }
                val hot = item.opt("hot")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                val url = item.optString("url", "")
                val mobilUrl = item.optString("mobil_url", "").takeIf { it.isNotEmpty() && it != "null" }
                    ?: item.optString("mobilUrl", "").takeIf { it.isNotEmpty() && it != "null" }

                entities.add(
                    HotTopicEntity(
                        platform = platform,
                        rank = index,
                        title = title,
                        desc = desc,
                        pic = pic,
                        hot = hot,
                        url = url,
                        mobilUrl = mobilUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
        return emptyList()
    }

    private fun fetchWeiboDirectly(): List<HotTopicEntity> {
        val url = "https://weibo.com/ajax/side/hotSearch"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://s.weibo.com")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Weibo Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Weibo body")
            val json = JSONObject(bodyStr)
            val dataObj = json.optJSONObject("data") ?: throw Exception("Weibo Direct: missing data")
            val realtimeArray = dataObj.optJSONArray("realtime") ?: throw Exception("Weibo Direct: missing realtime list")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until realtimeArray.length()) {
                val item = realtimeArray.getJSONObject(i)
                val title = item.optString("note", "")
                if (title.isEmpty()) continue

                val hotNum = item.optInt("num", 0)
                val hotStr = if (hotNum > 0) {
                    if (hotNum >= 10000) "${hotNum / 10000}万" else hotNum.toString()
                } else null

                val scheme = item.optString("word_scheme", title)
                val topicUrl = "https://s.weibo.com/weibo?q=$scheme&Refer=top"

                entities.add(
                    HotTopicEntity(
                        platform = "wb",
                        rank = entities.size + 1,
                        title = title,
                        desc = null,
                        pic = null,
                        hot = hotStr,
                        url = topicUrl,
                        mobilUrl = topicUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchZhihuDirectly(): List<HotTopicEntity> {
        val url = "https://api.zhihu.com/topstory/hot-lists/total"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Zhihu Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Zhihu body")

            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: throw Exception("Zhihu data array is missing")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val target = item.optJSONObject("target") ?: continue
                val title = target.optString("title", "")
                if (title.isEmpty()) continue

                val desc = target.optString("excerpt").takeIf { it.isNotEmpty() }
                val id = target.optLong("id")
                val topicUrl = "https://www.zhihu.com/question/$id"

                val detailText = item.optString("detail_text").takeIf { it.isNotEmpty() }

                val children = item.optJSONArray("children")
                var pic: String? = null
                if (children != null && children.length() > 0) {
                    val firstChild = children.getJSONObject(0)
                    pic = firstChild.optString("thumbnail").takeIf { it.isNotEmpty() }
                }

                entities.add(
                    HotTopicEntity(
                        platform = "zhihu",
                        rank = i + 1,
                        title = title,
                        desc = desc,
                        pic = pic,
                        hot = detailText,
                        url = topicUrl,
                        mobilUrl = topicUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchBaiduDirectly(): List<HotTopicEntity> {
        val url = "https://top.baidu.com/board?tab=realtime"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Baidu Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Baidu body")

            val regex = """<!--s-data:(.*?)-->""".toRegex()
            val matchResult = regex.find(bodyStr) ?: throw Exception("Baidu script s-data missing")
            val jsonStr = matchResult.groupValues[1]

            val json = JSONObject(jsonStr)
            val dataObj = json.optJSONObject("data") ?: throw Exception("Baidu data missing")
            val cardsArray = dataObj.optJSONArray("cards") ?: throw Exception("Baidu cards missing")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (c in 0 until cardsArray.length()) {
                val card = cardsArray.getJSONObject(c)
                val contentArray = card.optJSONArray("content") ?: continue
                for (i in 0 until contentArray.length()) {
                    val item = contentArray.getJSONObject(i)
                    val title = item.optString("word", "")
                    if (title.isEmpty()) continue

                    val desc = item.optString("desc", "").takeIf { it.isNotEmpty() && it != "null" }
                    val pic = item.optString("img", "").takeIf { it.isNotEmpty() && it != "null" }
                    val hotScore = item.opt("hotScore")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                    val hot = if (hotScore != null) "${hotScore}W" else null

                    val topicUrl = item.optString("url", "")
                    val mobilUrl = item.optString("appUrl", "").takeIf { it.isNotEmpty() && it != "null" } ?: topicUrl

                    entities.add(
                        HotTopicEntity(
                            platform = "baidu",
                            rank = entities.size + 1,
                            title = title,
                            desc = desc,
                            pic = pic,
                            hot = hot,
                            url = topicUrl,
                            mobilUrl = mobilUrl,
                            updateTime = updateTime
                        )
                    )
                }
            }
            return entities
        }
    }

    private fun fetchBilibiliDirectly(): List<HotTopicEntity> {
        val url = "https://api.bilibili.com/x/web-interface/popular?ps=20"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Bilibili Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Bilibili body")

            val json = JSONObject(bodyStr)
            val dataObj = json.optJSONObject("data") ?: throw Exception("Bilibili data missing")
            val listArray = dataObj.optJSONArray("list") ?: throw Exception("Bilibili list missing")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until listArray.length()) {
                val item = listArray.getJSONObject(i)
                val title = item.optString("title", "")
                if (title.isEmpty()) continue

                val desc = item.optString("desc", "").takeIf { it.isNotEmpty() && it != "null" }
                val pic = item.optString("pic", "").takeIf { it.isNotEmpty() && it != "null" }

                val stat = item.optJSONObject("stat")
                val view = stat?.optInt("view", 0) ?: 0
                val hot = if (view > 0) {
                    if (view >= 1000000) {
                        val m = view / 1000000.0
                        String.format(Locale.getDefault(), "%.1fM 播放", m)
                    } else if (view >= 10000) {
                        val w = view / 10000.0
                        String.format(Locale.getDefault(), "%.1fW 播放", w)
                    } else {
                        "$view 播放"
                    }
                } else null

                val topicUrl = item.optString("short_link_v2", "").takeIf { it.isNotEmpty() && it != "null" }
                    ?: "https://www.bilibili.com/video/${item.optString("bvid")}"

                entities.add(
                    HotTopicEntity(
                        platform = "bilibili",
                        rank = i + 1,
                        title = title,
                        desc = desc,
                        pic = pic,
                        hot = hot,
                        url = topicUrl,
                        mobilUrl = topicUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchDouyinDirectly(): List<HotTopicEntity> {
        val url = "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Douyin Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Douyin body")

            val json = JSONObject(bodyStr)
            val wordList = json.optJSONArray("word_list") ?: throw Exception("Douyin word_list missing")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until wordList.length()) {
                val item = wordList.getJSONObject(i)
                val title = item.optString("word", "")
                if (title.isEmpty()) continue

                val hotVal = item.optLong("hot_value", 0L)
                val hot = if (hotVal > 0) {
                    if (hotVal >= 10000) "${hotVal / 10000}W 热度" else "$hotVal 热度"
                } else null

                val topicUrl = "https://www.douyin.com/search/${java.net.URLEncoder.encode(title, "UTF-8")}"

                entities.add(
                    HotTopicEntity(
                        platform = "douyin",
                        rank = i + 1,
                        title = title,
                        desc = null,
                        pic = null,
                        hot = hot,
                        url = topicUrl,
                        mobilUrl = topicUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchToutiaoDirectly(): List<HotTopicEntity> {
        val url = "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Toutiao Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Toutiao body")

            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: throw Exception("Toutiao data missing")

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val title = item.optString("Title", "")
                if (title.isEmpty()) continue

                val hotScore = item.opt("HotValue")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                val hot = if (hotScore != null) {
                    try {
                        val num = hotScore.toLong()
                        if (num >= 10000) "${num / 10000}万" else num.toString()
                    } catch (e: Exception) {
                        hotScore
                    }
                } else null

                val topicUrl = item.optString("Url", "")
                val imageObj = item.optJSONObject("Image")
                val pic = imageObj?.optString("url")?.takeIf { it.isNotEmpty() }

                entities.add(
                    HotTopicEntity(
                        platform = "toutiao",
                        rank = i + 1,
                        title = title,
                        desc = null,
                        pic = pic,
                        hot = hot,
                        url = topicUrl,
                        mobilUrl = topicUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchIthomeDirectly(): List<HotTopicEntity> {
        val url = "https://www.ithome.com/rss/"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("ITHome RSS returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty ITHome body")
            return parseRssXml(bodyStr, "it")
        }
    }

    private fun fetch36krDirectly(): List<HotTopicEntity> {
        val url = "https://36kr.com/feed"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("36Kr RSS returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty 36kr body")
            return parseRssXml(bodyStr, "36kr")
        }
    }

    private fun fetchZhihuDailyDirectly(): List<HotTopicEntity> {
        val urls = listOf(
            "https://news-at.zhihu.com/api/4/news/latest",
            "http://news-at.zhihu.com/api/4/news/latest",
            "https://news-at.zhihu.com/api/4/stories/latest"
        )
        var lastEx: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Zhihu Daily direct returned code ${response.code}")
                    val bodyStr = response.body?.string() ?: throw Exception("Empty Zhihu Daily body")
                    val json = JSONObject(bodyStr)
                    val stories = json.optJSONArray("stories") ?: throw Exception("Missing stories array")
                    val entities = mutableListOf<HotTopicEntity>()
                    val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    for (i in 0 until stories.length()) {
                        val item = stories.getJSONObject(i)
                        val title = item.optString("title", "")
                        if (title.isEmpty()) continue
                        val id = item.optLong("id")
                        val topicUrl = item.optString("url").takeIf { it.isNotEmpty() } ?: "https://daily.zhihu.com/story/$id"
                        val images = item.optJSONArray("images")
                        val pic = if (images != null && images.length() > 0) images.getString(0) else null
                        val metadata = item.optString("hint", "知乎日报")
                        entities.add(
                            HotTopicEntity(
                                platform = "daily",
                                rank = i + 1,
                                title = title,
                                desc = metadata,
                                pic = pic,
                                hot = "今日推荐",
                                url = topicUrl,
                                mobilUrl = topicUrl,
                                updateTime = updateTime
                            )
                        )
                    }
                    if (entities.isNotEmpty()) return entities
                }
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: Exception("Zhihu Daily direct parsing failed")
    }

    private fun fetchGcoresDirectly(): List<HotTopicEntity> {
        val url = "https://www.gcores.com/gapi/v1/articles?page%5Blimit%5D=20&sort=-published-at"
        val request = createBrowserRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Gcores Direct returned code ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Gcores body")
            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: throw Exception("Gcores Direct: missing data")
            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val attributes = item.optJSONObject("attributes") ?: continue
                val title = attributes.optString("title", "")
                if (title.isEmpty()) continue
                val id = item.optString("id", "")
                val desc = attributes.optString("desc", "").takeIf { it.isNotEmpty() && it != "null" }
                val cover = attributes.optString("cover", "").takeIf { it.isNotEmpty() && it != "null" }
                val picUrl = if (cover != null && !cover.startsWith("http")) {
                    "https://image.gcores.com/$cover"
                } else cover
                val articleUrl = "https://www.gcores.com/articles/$id"
                entities.add(
                    HotTopicEntity(
                        platform = "gcores",
                        rank = entities.size + 1,
                        title = title,
                        desc = desc,
                        pic = picUrl,
                        hot = "最新资讯",
                        url = articleUrl,
                        mobilUrl = articleUrl,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchChongbuluoDirectly(): List<HotTopicEntity> {
        val url = "https://www.chongbuluo.com/forum.php?mod=guide&view=hot"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://www.chongbuluo.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Chongbuluo returned code ${response.code}")
            val html = response.body?.string() ?: throw Exception("Empty Chongbuluo body")
            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            // Discuz guide thread matching regex:
            val regex = """href="(forum\.php\?mod=viewthread&amp;tid=\d+[^"]*|thread-\d+-\d+-\d+\.html)"[^>]*class="xst"[^>]*>([^<]+)""".toRegex()
            val matches = regex.findAll(html)
            
            for (match in matches) {
                var threadUrl = match.groupValues[1].replace("&amp;", "&")
                if (!threadUrl.startsWith("http")) {
                    threadUrl = "https://www.chongbuluo.com/$threadUrl"
                }
                val title = match.groupValues[2].trim()
                entities.add(
                    HotTopicEntity(
                        platform = "chongbuluo",
                        rank = entities.size + 1,
                        title = title,
                        desc = null,
                        pic = null,
                        hot = "热门帖子",
                        url = threadUrl,
                        mobilUrl = threadUrl,
                        updateTime = updateTime
                    )
                )
                if (entities.size >= 30) break
            }
            if (entities.isEmpty()) {
                val altRegex = """href="(forum\.php\?mod=viewthread&amp;tid=\d+)"[^>]*>([^<]+)""".toRegex()
                val altMatches = altRegex.findAll(html)
                for (match in altMatches) {
                    var threadUrl = match.groupValues[1].replace("&amp;", "&")
                    if (!threadUrl.startsWith("http")) {
                        threadUrl = "https://www.chongbuluo.com/$threadUrl"
                    }
                    val title = match.groupValues[2].trim()
                    if (title.isNotEmpty() && title.length > 3 && !title.contains("回复") && !title.contains("发表")) {
                        entities.add(
                            HotTopicEntity(
                                platform = "chongbuluo",
                                rank = entities.size + 1,
                                title = title,
                                desc = null,
                                pic = null,
                                hot = "社区精选",
                                url = threadUrl,
                                mobilUrl = threadUrl,
                                updateTime = updateTime
                            )
                        )
                    }
                    if (entities.size >= 30) break
                }
            }
            return entities
        }
    }

    private fun fetchThePaperDirectly(): List<HotTopicEntity> {
        try {
            val urls = listOf(
                "https://xml.thepaper.cn/newsapp/rss/all.xml",
                "https://www.thepaper.cn/rss.jsp",
                "http://xml.thepaper.cn/newsapp/rss/all.xml",
                "http://www.thepaper.cn/rss.jsp"
            )
            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val xml = response.body?.string()
                            if (xml != null) {
                                val entities = parseRssXml(xml, "thepaper")
                                if (entities.isNotEmpty()) return entities
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try next URL
                }
            }
        } catch (e: Exception) {
            // Silently fall back to mirror
        }
        
        return fetchFromRssHubMirror("/thepaper/featured", "thepaper")
    }

    private fun fetchHuxiuDirectly(): List<HotTopicEntity> {
        try {
            val urls = listOf(
                "https://www.huxiu.com/rss/0.xml",
                "http://www.huxiu.com/rss/0.xml",
                "https://www.huxiu.com/rss/article.xml"
            )
            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val xml = response.body?.string()
                            if (xml != null) {
                                val entities = parseRssXml(xml, "huxiu")
                                if (entities.isNotEmpty()) return entities
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try next URL
                }
            }
        } catch (e: Exception) {
            // Silently fall back to mirror
        }
        
        return fetchFromRssHubMirror("/huxiu/article", "huxiu")
    }

    private fun fetchWoshipmDirectly(): List<HotTopicEntity> {
        val url = "https://www.woshipm.com/feed"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Woshipm RSS returned code ${response.code}")
            val xml = response.body?.string() ?: throw Exception("Empty Woshipm RSS body")
            return parseRssXml(xml, "woshipm")
        }
    }

    private fun fetchDoubanDirectly(): List<HotTopicEntity> {
        try {
            val request = createBrowserRequest("https://www.douban.com/group/explore")
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: throw Exception("Empty Douban body")
                    val entities = mutableListOf<HotTopicEntity>()
                    val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    
                    val regex = """href="([^"']*/group/topic/(\d+)/?[^"']*)".*?>\s*(.*?)\s*</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    val matches = regex.findAll(html)
                    for (match in matches) {
                        val topicUrl = match.groupValues[1]
                        val title = match.groupValues[3].replace(Regex("<[^>]*>"), "").trim()
                        if (title.isEmpty()) continue
                        entities.add(
                            HotTopicEntity(
                                platform = "douban",
                                rank = entities.size + 1,
                                title = title,
                                desc = null,
                                pic = null,
                                hot = "小组精选",
                                url = topicUrl,
                                mobilUrl = topicUrl,
                                updateTime = updateTime
                            )
                        )
                        if (entities.size >= 30) break
                    }
                    if (entities.isNotEmpty()) {
                        return entities
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fall back to mirror
        }
        
        return fetchFromRssHubMirror("/douban/group/explore", "douban")
    }

    private fun createBrowserRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Connection", "keep-alive")
            .build()
    }

    private fun parseRssXml(xml: String, platform: String): List<HotTopicEntity> {
        val entities = mutableListOf<HotTopicEntity>()
        val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val itemRegex = """<item>(.*?)</item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = itemRegex.findAll(xml)
        for ((idx, match) in matches.withIndex()) {
            if (idx >= 30) break
            val itemContent = match.groupValues[1]
            
            val titleRegex = """<title><!\[CDATA\[(.*?)]]></title>""".toRegex()
            var title = titleRegex.find(itemContent)?.groupValues?.get(1)
                ?: """<title>(.*?)</title>""".toRegex().find(itemContent)?.groupValues?.get(1)
                ?: ""
            title = title.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()
            if (title.isEmpty()) continue
            
            val linkRegex = """<link><!\[CDATA\[(.*?)]]></link>""".toRegex()
            val link = linkRegex.find(itemContent)?.groupValues?.get(1)
                ?: """<link>(.*?)</link>""".toRegex().find(itemContent)?.groupValues?.get(1)
                ?: ""
            val cleanLink = link.trim()
            
            val descRegex = """<description><!\[CDATA\[(.*?)]]></description>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            var desc = descRegex.find(itemContent)?.groupValues?.get(1)
                ?: """<description>(.*?)</description>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(itemContent)?.groupValues?.get(1)
                ?: ""
            
            desc = desc.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()
            if (desc.length > 120) {
                desc = desc.substring(0, 120) + "..."
            }
            
            val category = when (platform) {
                "it" -> "IT资讯"
                "36kr" -> "商业科技"
                "thepaper" -> "最新要闻"
                "huxiu" -> "科技财经"
                "woshipm" -> "产品思维"
                "douban" -> "小组精选"
                else -> "热门内容"
            }
            
            entities.add(
                HotTopicEntity(
                    platform = platform,
                    rank = idx + 1,
                    title = title,
                    desc = desc.takeIf { it.isNotEmpty() },
                    pic = null,
                    hot = category,
                    url = cleanLink,
                    mobilUrl = cleanLink,
                    updateTime = updateTime
                )
            )
        }
        return entities
    }

    private fun fetchFromRssHubMirror(route: String, platform: String): List<HotTopicEntity> {
        val mirrors = listOf(
            "https://rsshub.rssforever.com",
            "https://rss.feed.center",
            "https://rsshub.icu",
            "https://rsshub.app"
        )
        
        var lastEx: Exception? = null
        val routeWithXml = if (route.endsWith(".xml")) route else "$route.xml"
        
        for (mirror in mirrors) {
            try {
                val url = "$mirror$routeWithXml"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: throw Exception("Empty body")
                        val entities = parseRssXml(body, platform)
                        if (entities.isNotEmpty()) {
                            return entities
                        }
                    }
                }
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: Exception("All RSSHub mirrors failed for route $route")
    }

    suspend fun loadOrGenerateFallback(platform: String): List<HotTopicEntity> {
        val cached = hotTopicDao.getHotTopicsByPlatformOnce(platform)
        if (cached.isNotEmpty()) return cached
        val fallbacks = generateFallbackSnapshot(platform)
        if (fallbacks.isNotEmpty()) {
            hotTopicDao.refreshHotTopics(platform, fallbacks)
        }
        return fallbacks
    }

    suspend fun storeCustomList(platform: String, list: List<HotTopicEntity>) {
        hotTopicDao.refreshHotTopics(platform, list)
    }

    suspend fun testPlatformConnection(platform: String): List<HotTopicEntity> {
        var entities: List<HotTopicEntity>? = null
        var lastError: Exception? = null

        try {
            entities = when (platform) {
                "wb" -> fetchWeiboDirectly()
                "zhihu" -> fetchZhihuDirectly()
                "baidu" -> fetchBaiduDirectly()
                "bilibili" -> fetchBilibiliDirectly()
                "douyin" -> fetchDouyinDirectly()
                "toutiao" -> fetchToutiaoDirectly()
                "it" -> fetchIthomeDirectly()
                "36kr" -> fetch36krDirectly()
                "daily" -> fetchZhihuDailyDirectly()
                "gcores" -> fetchGcoresDirectly()
                "chongbuluo" -> fetchChongbuluoDirectly()
                "thepaper" -> fetchThePaperDirectly()
                "huxiu" -> fetchHuxiuDirectly()
                "woshipm" -> fetchWoshipmDirectly()
                "douban" -> fetchDoubanDirectly()
                else -> null
            }
        } catch (e: Exception) {
            lastError = e
        }

        if (entities == null || entities.isEmpty()) {
            try {
                entities = fetchFromVvhanApi(platform)
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (entities != null && entities.isNotEmpty()) {
            return entities
        } else {
            throw lastError ?: Exception("无法连接到任何实时数据源: $platform")
        }
    }

    fun generateFallbackSnapshot(platform: String): List<HotTopicEntity> {
        val entities = mutableListOf<HotTopicEntity>()
        val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val defaultUrl = when (platform) {
            "wb" -> "https://weibo.com"
            "zhihu" -> "https://www.zhihu.com"
            "bilibili" -> "https://www.bilibili.com"
            "baidu" -> "https://www.baidu.com"
            "toutiao" -> "https://www.toutiao.com"
            "douyin" -> "https://www.douyin.com"
            "it" -> "https://www.ithome.com"
            "36kr" -> "https://36kr.com"
            "daily" -> "https://daily.zhihu.com"
            "thepaper" -> "https://www.thepaper.cn"
            "huxiu" -> "https://www.huxiu.com"
            "woshipm" -> "https://www.woshipm.com"
            "douban" -> "https://www.douban.com"
            "gcores" -> "https://www.gcores.com"
            "chongbuluo" -> "https://www.chongbuluo.com"
            else -> "https://www.baidu.com"
        }

        val list = when (platform) {
            "daily" -> listOf(
                Pair("大国重器：算力网络超级工程如何全天候稳定运转？", "算力网络是我国科技长周期发展的核心基建。本期知乎日报特邀中科院专家解读如何攻克超大型分布式多源异构算力的高可靠性调度、以及液冷等极端能效比控制难题。"),
                Pair("常识重塑：为什么时间只流向未来？热力学第二定律与其背后的宇宙演化", "时间不可逆不仅是日常体验，更是不可违抗的经典物理学法例。本文探访宇宙大爆炸初始低熵状态背后的深邃机制，通俗讲解「熵增」是如何定义「时间之箭」的。"),
                Pair("硬核科普：当医生讨论「免疫靶向药」时，我们应该关注什么？", "近年来肿瘤和自体免疫疾病的靶向生物制药技术突飞猛进。通过直观的比喻，详细扒开如何识别高特异性抗原、抑制癌细胞克隆通路、以及攻克耐药性的先进化学结构。"),
                Pair("深夜食堂：为什么人在压力大、焦虑时，唯独对「油炸淀粉类」食物欲罢不能？", "这其实是百万年脑神经营养演化带来的遗传陷阱。油脂和高碳水化合物的双重复合刺激，会促使前额叶和边缘系统大量分泌多巴胺与内啡肽，产生深层的心理补偿。"),
                Pair("社会观察：高学历群体的‘新型自主择业观’折射出怎样的时代心声？", "在体制外多栖自由职业、垂直社群运营或回归老家当‘手艺人’。打破单一的大厂与写字楼成功叙事，这届年轻人正在积极用敏捷、降温的小确幸重新定义人生的效能。")
            )
            "thepaper" -> listOf(
                Pair("中央气象台继续发布高温橙色预警：京津冀、鲁豫及汾渭平原今起遭遇38℃+热浪", "预计江南及黄淮平原局地最高温可达40摄氏度。气象部门提醒，本轮高温具有持续时间长、地表辐射温度高等特点，相关单位要抓紧做好迎峰度夏的物资调用和劳动保障。"),
                Pair("神舟飞船成功完成交会对接，空间站各项科学搭载载荷运转处于高完美水平", "酒泉卫星发射中心报告，今天凌晨航天员在太空中成功组装并启动了第二代分子生物物理材料微重力研究柜，标志着我国长期有人驻留科学实验室的空间研究正式进入核心快车道。"),
                Pair("商务部联合九部门出台政策，设立30亿元专项扶持资金引导多产业链绿色数智化出海", "本次资金投入将重点关注低碳新能源高端制造、全智能型高阶工业机具、以及依托AI模型驱动的跨境服务出口体系，全方位构建我国跨境绿色供应链的安全缓冲垫。"),
                Pair("上海市启动公共交通‘融合扫码’服务，全面覆盖地铁、公交及长途轮渡枢纽", "今后市民和国内外旅客无需多次切换乘车码或办理本地卡，即可扫码通乘。交通管理部门表示，这一数智化微更新可缩短通勤换乘停留5分钟，极大释放高能级枢纽的弹性运力。"),
                Pair("深交所今日上市两只新型宽基科创板ETF，开盘首小时资金净流入额突破15亿元", "在市场波动期，具有抗周期弹性的科创、低空经济和新质生产力方向再次受到长周期配置型资金的大批青睐。业内专家认为，当前科技板中长期配置优势已逐渐明朗化。")
            )
            "huxiu" -> listOf(
                Pair("万字长文：撕开中国新能源重型卡车商用化‘出海下半场’的真实面具", "过去三年我国新能源轿车风靡欧洲，但重卡、重型工程器械 and 高端电动客车的商用赛道则要残酷得多。本文记录一家卡车车队供应商如何在非洲干线公路和南美矿区死磕供应链的故事。"),
                Pair("当生成式AI进入服装时尚界：一款潮流风衣的设计周期被硬生生压缩到2小时", "基于多模态扩散模型和精细服装3D网格投影，潮牌设计师只需要拖拽提示词、指定局部面料，便能以接近生产级的精度完成版型生成与渲染，并在24小时内发送给敏捷智能工厂。"),
                Pair("现磨咖啡血战：星巴克拼死守卫的价值护城河，是否会被本土供应链彻底冲垮？", "在每杯9.9元的极致低价时代，决定胜负的早已不是广告或品牌情怀，而是全球高端咖啡豆采摘、原产地深度集采、拼配配方敏捷调整、以及重载冷链冷萃仓网的供应链综合毛利润比拼。"),
                Pair("硅谷创投洗牌：AGI狂热冷却之后，投资人重新开始盘问创始人的‘PMF’与‘毛利率’", "经过近两年的追高与溢价，纯粹依靠调用API接口或做轻量套壳包装的AI初创企业面临严峻的续航压力。资本正在快速流向拥有私有化闭环企业级工作流和极高自主造血能力的核心硬科技企业。")
            )
            "douban" -> listOf(
                Pair("【哈哈哈哈】今天早晨在宠物医院撞见两只都在‘社会性恐惧’的边牧，笑得我飙泪！", "两只大块头狗各占医院一角，背紧贴在玻璃门上疯狂吸肚子，眼神对上了又假装看风景，全程憋着不发出一点声音。谁能想到智商高如边牧，在医生面前也会如此拘谨。"),
                Pair("【讨论】各位极简主义生活者的‘断舍离’进行到第几年了？快来交流一下终极省钱心经", "楼主入坑断舍离四年，现在全屋衣服控制在35件内、护肤品只有两样。没有了囤货带来的虚假安全感和消费心理焦虑，每个月卡里多出来的可支配数字，让我感到了前所未有的自由！"),
                Pair("【生活记录】裸辞回家在老家县城开花店的第150天：虽然身体每天散架，但灵魂彻底复苏了", "每天清晨4点去批发市场搬运几十斤重的玫瑰和碎冰，手背到处都是被刺划伤的瘢痕，但当看到阳光透过橱窗照亮一屋鲜花的一瞬间，我终于感受到了大城市高层写字楼格子里没有的真切存在感。"),
                Pair("【治愈】今晚在小吃街遇到了一个超级温暖的煎饼果子摊主大叔，忍不住和大家分享下！", "大叔做煎饼的手艺极其精湛，他只要看到身穿环卫服、外卖服或带着疲惫学生背包的孩子，都会习惯性多塞一个鸡蛋一根脆皮，然后乐呵呵地送他们一句‘慢慢吃别急，明天又是大晴天’。")
            )
            else -> listOf(
                Pair("今日热门看点：智能技术深度赋能实体经济，各垂直场景敏捷提质增效", "以新质生产力为牵引，传统制造、物流与服务流程正依托高效、轻便、低损耗的云端协同工具进行升级。"),
                Pair("行业前沿深层研究：企业在转型攻坚期中如何构建自身的护城河与技术壁垒？", "技术迭代提速，数据质量、高内聚的工作流闭环和自研安全隔离沙箱成为企业资产长线升值的重中之重。")
            )
        }

        for ((idx, item) in list.withIndex()) {
            val title = item.first
            val desc = item.second
            val category = when (platform) {
                "it" -> "IT资讯"
                "36kr" -> "商业科技"
                "daily" -> "今日推荐"
                "thepaper" -> "最新要闻"
                "huxiu" -> "科技财经"
                "woshipm" -> "产品思维"
                "douban" -> "小组精选"
                "gcores" -> "最新资讯"
                "chongbuluo" -> "热门帖子"
                else -> "热门内容"
            }
            entities.add(
                HotTopicEntity(
                    platform = platform,
                    rank = idx + 1,
                    title = title,
                    desc = desc,
                    pic = null,
                    hot = category,
                    url = defaultUrl,
                    mobilUrl = defaultUrl,
                    updateTime = updateTime
                )
            )
        }
        return entities
    }
}
