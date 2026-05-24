package com.example

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `test main activity launch`() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertNotNull(activity)
      }
    }
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("今日热搜", appName)
  }

  @Test
  fun testZhihuMobileApi() {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("https://api.zhihu.com/topstory/hot-lists/total")
        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
        .build()
    try {
        val response = client.newCall(request).execute()
        println("--- ZHIHU MOBILE RESPONSE CODE: ${response.code} ---")
        val bodyStr = response.body?.string() ?: ""
        val json = JSONObject(bodyStr)
        val dataArray = json.optJSONArray("data")
        println("--- PARSING ${dataArray?.length() ?: 0} ITEMS ---")
        if (dataArray != null) {
            for (i in 0 until Math.min(dataArray.length(), 5)) {
                val item = dataArray.getJSONObject(i)
                val target = item.optJSONObject("target")
                val title = target?.optString("title")
                val excerpt = target?.optString("excerpt")
                val id = target?.optLong("id")
                val detailText = item.optString("detail_text")
                val children = item.optJSONArray("children")
                var thumbnail: String? = null
                if (children != null && children.length() > 0) {
                    val firstChild = children.getJSONObject(0)
                    thumbnail = firstChild.optString("thumbnail")
                }
                println("RANK: ${i + 1}")
                println("TITLE: $title")
                println("DESC: $excerpt")
                println("ID: $id")
                println("HOT: $detailText")
                println("IMAGE: $thumbnail")
                println("----------------------------------------------")
            }
        }
    } catch (e: Exception) {
        println("--- API EXCEPTION: ---")
        e.printStackTrace()
    }
  }

  @Test
  fun testToutiaoMobileApi() {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .build()
    try {
        val response = client.newCall(request).execute()
        println("--- TOUTIAO DIRECT RESPONSE CODE: ${response.code} ---")
        val bodyStr = response.body?.string() ?: ""
        val json = JSONObject(bodyStr)
        val dataArray = json.optJSONArray("data")
        if (dataArray != null && dataArray.length() > 0) {
            val first = dataArray.getJSONObject(0)
            println("--- TOUTIAO ITEM KEYS: ${first.keys().asSequence().toList()} ---")
            println("--- FULL FIRST ITEM: $first ---")
        } else {
            println("--- TOUTIAO NO DATA ARRAY ---")
        }
    } catch (e: Exception) {
        println("--- TOUTIAO EXCEPTION: ---")
        e.printStackTrace()
    }
  }

  @Test
  fun testLiveApi() {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("https://api.vvhan.com/api/hotlist?type=wb")
        .build()
    try {
        val response = client.newCall(request).execute()
        println("--- API RESPONSE CODE: ${response.code} ---")
        println("--- API RESPONSE BODY: ---")
        val bodyStr = response.body?.string()
        println(bodyStr)
    } catch (e: Exception) {
        println("--- API EXCEPTION: ---")
        e.printStackTrace()
    }
  }
}


