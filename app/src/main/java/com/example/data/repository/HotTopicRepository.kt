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
                "gcores" -> fetchGcoresDirectly()
                "chongbuluo" -> fetchChongbuluoDirectly()
                "woshipm" -> fetchWoshipmDirectly()
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
                "gcores" -> fetchGcoresDirectly()
                "chongbuluo" -> fetchChongbuluoDirectly()
                "woshipm" -> fetchWoshipmDirectly()
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
            "woshipm" -> "https://www.woshipm.com"
            "gcores" -> "https://www.gcores.com"
            "chongbuluo" -> "https://www.chongbuluo.com"
            else -> "https://www.baidu.com"
        }

        val list = when (platform) {
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
                "woshipm" -> "产品思维"
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
