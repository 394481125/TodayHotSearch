package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "platform_settings")
data class PlatformSettingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isVisible: Boolean = true,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0
)
