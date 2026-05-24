package com.example.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface VvhanApiService {
    @GET("api/hotlist")
    suspend fun getHotList(
        @Query("type") type: String
    ): ResponseBody
}
