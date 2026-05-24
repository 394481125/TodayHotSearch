package com.example

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExampleUnitTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun testZhihuMobileApi() {
        val url = "https://api.zhihu.com/topstory/hot-lists/total"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                println("ZHIHU MOBILE STATUS: ${response.code}")
                val bodyStr = response.body?.string() ?: ""
                println("ZHIHU MOBILE BODY LENGTH: ${bodyStr.length}")
                if (bodyStr.length > 500) {
                    println("ZHIHU MOBILE BODY START: ${bodyStr.substring(0, 500)}")
                }
                
                val json = JSONObject(bodyStr)
                val dataArray = json.optJSONArray("data")
                println("ZHIHU MOBILE DATA SIZE: ${dataArray?.length()}")
                if (dataArray != null && dataArray.length() > 0) {
                    val first = dataArray.getJSONObject(0)
                    println("ZHIHU MOBILE FIRST ITEM: $first")
                }
            }
        } catch (e: Exception) {
            println("ZHIHU MOBILE EXCEPTION: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun testAllVvhanApis() {
        val platforms = listOf(
            "wb", "zhihu", "bilibili", "baidu", "toutiao", "douyin", "it", "36kr", "daily",
            "thepaper", "hupu", "huxiu", "woshipm", "douban", "jike", "gcores", "chongbuluo", "cbl"
        )
        for (p in platforms) {
            val url = "https://api.vvhan.com/api/hotlist?type=$p"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val success = json.optBoolean("success", false)
                    val data = json.optJSONArray("data")
                    val len = data?.length() ?: 0
                    println("API TEST PLATFORM: $p | Success: $success | Items: $len | Url: $url")
                    if (len > 0) {
                        println("  Sample: " + data?.getJSONObject(0)?.optString("title"))
                    }
                }
            } catch (e: Exception) {
                println("API TEST PLATFORM: $p | Error: ${e.message}")
            }
        }
    }
}


