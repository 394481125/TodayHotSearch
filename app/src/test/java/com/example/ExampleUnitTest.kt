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
}


