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
                // If fallback API fails too, propagate original direct scrape exception
                throw lastError ?: e
            }
        }

        if (entities != null && entities.isNotEmpty()) {
            hotTopicDao.refreshHotTopics(platform, entities)
        } else {
            throw Exception("Failed to load hot topics from either direct parsing or fallback API.")
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

            val itemRegex = """<item>(.*?)</item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = itemRegex.findAll(bodyStr)

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for ((idx, match) in matches.withIndex()) {
                if (idx >= 30) break
                val itemContent = match.groupValues[1]

                val titleRegex = """<title><!\[CDATA\[(.*?)]]></title>""".toRegex()
                val title = titleRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<title>(.*?)</title>""".toRegex().find(itemContent)?.groupValues?.get(1)
                    ?: ""

                if (title.isEmpty()) continue

                val linkRegex = """<link><!\[CDATA\[(.*?)]]></link>""".toRegex()
                val link = linkRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<link>(.*?)</link>""".toRegex().find(itemContent)?.groupValues?.get(1)
                    ?: "https://www.ithome.com/"

                val descRegex = """<description><!\[CDATA\[(.*?)]]></description>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                var desc = descRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<description>(.*?)</description>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(itemContent)?.groupValues?.get(1)
                    ?: ""

                desc = desc.replace(Regex("<[^>]*>"), "").trim()
                if (desc.length > 120) {
                    desc = desc.substring(0, 120) + "..."
                }

                entities.add(
                    HotTopicEntity(
                        platform = "it",
                        rank = idx + 1,
                        title = title,
                        desc = desc.takeIf { it.isNotEmpty() },
                        pic = null,
                        hot = "IT资讯",
                        url = link,
                        mobilUrl = link,
                        updateTime = updateTime
                    )
                )
            }
            return entities
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

            val itemRegex = """<item>(.*?)</item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = itemRegex.findAll(bodyStr)

            val entities = mutableListOf<HotTopicEntity>()
            val updateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for ((idx, match) in matches.withIndex()) {
                if (idx >= 30) break
                val itemContent = match.groupValues[1]

                val titleRegex = """<title><!\[CDATA\[(.*?)]]></title>""".toRegex()
                val title = titleRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<title>(.*?)</title>""".toRegex().find(itemContent)?.groupValues?.get(1)
                    ?: ""

                if (title.isEmpty()) continue

                val linkRegex = """<link><!\[CDATA\[(.*?)]]></link>""".toRegex()
                val link = linkRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<link>(.*?)</link>""".toRegex().find(itemContent)?.groupValues?.get(1)
                    ?: "https://36kr.com/"

                val descRegex = """<description><!\[CDATA\[(.*?)]]></description>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                var desc = descRegex.find(itemContent)?.groupValues?.get(1)
                    ?: """<description>(.*?)</description>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(itemContent)?.groupValues?.get(1)
                    ?: ""

                desc = desc.replace(Regex("<[^>]*>"), "").trim()
                if (desc.length > 120) {
                    desc = desc.substring(0, 120) + "..."
                }

                entities.add(
                    HotTopicEntity(
                        platform = "36kr",
                        rank = idx + 1,
                        title = title,
                        desc = desc.takeIf { it.isNotEmpty() },
                        pic = null,
                        hot = "商业科技",
                        url = link,
                        mobilUrl = link,
                        updateTime = updateTime
                    )
                )
            }
            return entities
        }
    }

    private fun fetchZhihuDailyDirectly(): List<HotTopicEntity> {
        val url = "https://news-at.zhihu.com/api/4/news/latest"
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
            return entities
        }
    }
}
