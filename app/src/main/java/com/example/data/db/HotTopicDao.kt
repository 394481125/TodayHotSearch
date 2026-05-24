package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HotTopicDao {
    @Query("SELECT * FROM hot_topics WHERE platform = :platform ORDER BY rank ASC")
    fun getHotTopicsByPlatform(platform: String): Flow<List<HotTopicEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHotTopics(topics: List<HotTopicEntity>)

    @Query("DELETE FROM hot_topics WHERE platform = :platform")
    suspend fun deleteHotTopicsByPlatform(platform: String)

    @Transaction
    suspend fun refreshHotTopics(platform: String, topics: List<HotTopicEntity>) {
        deleteHotTopicsByPlatform(platform)
        insertHotTopics(topics)
    }

    // Platform settings customization queries
    @Query("SELECT * FROM platform_settings ORDER BY isPinned DESC, sortOrder ASC")
    fun getPlatformSettings(): Flow<List<PlatformSettingEntity>>

    @Query("SELECT * FROM platform_settings ORDER BY isPinned DESC, sortOrder ASC")
    suspend fun getPlatformSettingsOnce(): List<PlatformSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlatformSettings(settings: List<PlatformSettingEntity>)
}
