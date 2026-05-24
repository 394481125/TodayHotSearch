package com.example.data.db

import androidx.room.Entity

@Entity(tableName = "hot_topics", primaryKeys = ["platform", "rank"])
data class HotTopicEntity(
    val platform: String,
    val rank: Int,
    val title: String,
    val desc: String?,
    val pic: String?,
    val hot: String?,
    val url: String,
    val mobilUrl: String?,
    val updateTime: String,
    val cacheTimestamp: Long = System.currentTimeMillis()
)
