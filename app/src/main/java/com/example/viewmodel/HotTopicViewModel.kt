package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.VvhanApiService
import com.example.data.db.AppDatabase
import com.example.data.db.HotTopicEntity
import com.example.data.db.PlatformSettingEntity
import com.example.data.repository.HotTopicRepository
import com.example.ui.theme.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

data class PlatformInfo(
    val id: String,
    val name: String,
    val accentColor: androidx.compose.ui.graphics.Color
)

data class DiagnosticResult(
    val platformId: String,
    val status: String, // "IDLE" (未测试), "TESTING" (测试中), "SUCCESS" (可读取), "FAILED" (无法读取)
    val latencyMs: Long = 0L,
    val count: Int = 0,
    val latestTime: String = "",
    val top1Topic: String = "",
    val errorDetail: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class HotTopicViewModel(application: Application) : AndroidViewModel(application) {

    private val database: AppDatabase = AppDatabase.getDatabase(application)

    private val apiService: VvhanApiService = Retrofit.Builder()
        .baseUrl("https://api.vvhan.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Referer", "https://hot.vvhan.com/")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(
            retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
            )
        )
        .build()
        .create(VvhanApiService::class.java)

    private val repository: HotTopicRepository = HotTopicRepository(apiService, database.hotTopicDao())

    private val sharedPrefs = application.getSharedPreferences("hot_search_prefs", Context.MODE_PRIVATE)

    // Full static configurations of all 14 brand identifiers
    val fullPlatformList = listOf(
        PlatformInfo("wb", "微博", BrandWeibo),
        PlatformInfo("zhihu", "知乎", BrandZhihu),
        PlatformInfo("bilibili", "B站", BrandBilibili),
        PlatformInfo("baidu", "百度", BrandBaidu),
        PlatformInfo("toutiao", "头条", BrandToutiao),
        PlatformInfo("douyin", "抖音", BrandDouyin),
        PlatformInfo("it", "IT之家", BrandItHome),
        PlatformInfo("36kr", "36氪", Brand36Kr),
        PlatformInfo("daily", "知乎日报", BrandZhihuDaily),
        PlatformInfo("thepaper", "澎湃新闻", BrandThePaper),
        PlatformInfo("hupu", "虎扑步行街", BrandHupu),
        PlatformInfo("huxiu", "虎嗅网", BrandHuxiu),
        PlatformInfo("woshipm", "产品经理", BrandWoshipm),
        PlatformInfo("douban", "豆瓣小组", BrandDouban),
        PlatformInfo("gcores", "机核", BrandGcores),
        PlatformInfo("chongbuluo", "虫部落", BrandChongbuluo)
    )

    private val _selectedPlatform = MutableStateFlow("wb")
    val selectedPlatform: StateFlow<String> = _selectedPlatform.asStateFlow()

    private val _showDashboard = MutableStateFlow(true)
    val showDashboard: StateFlow<Boolean> = _showDashboard.asStateFlow()

    private val _diagnosticList = MutableStateFlow<Map<String, DiagnosticResult>>(emptyMap())
    val diagnosticList: StateFlow<Map<String, DiagnosticResult>> = _diagnosticList.asStateFlow()

    private val _isTestingAll = MutableStateFlow(false)
    val isTestingAll: StateFlow<Boolean> = _isTestingAll.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Card Size Preference State: "small" (dense), "medium" (normal), "large" (spacious)
    private val _cardSize = MutableStateFlow(sharedPrefs.getString("card_size", "medium") ?: "medium")
    val cardSize: StateFlow<String> = _cardSize.asStateFlow()

    // Database configurations flow
    val platformSettings: StateFlow<List<PlatformSettingEntity>> = AppDatabase.getDatabase(application).hotTopicDao().getPlatformSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered platform tabs mapped reactively based on user's check toggles
    val visiblePlatforms: StateFlow<List<PlatformInfo>> = platformSettings
        .map { settings ->
            settings.filter { it.isVisible }.map { setting ->
                fullPlatformList.firstOrNull { it.id == setting.id }
                    ?: PlatformInfo(setting.id, setting.name, BrandWeibo)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Collect cached hot topics reactively as platform selection changes
    val hotTopics: StateFlow<List<HotTopicEntity>> = _selectedPlatform
        .flatMapLatest { platform ->
            repository.getCachedTopics(platform)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Initialise database settings for all defined systems, adding any new ones dynamically (to support older DB migrations)
        viewModelScope.launch {
            val dao = database.hotTopicDao()
            val existing = dao.getPlatformSettingsOnce()
            
            val existingIds = existing.map { it.id }.toSet()
            val missingPlatforms = fullPlatformList.filter { it.id !in existingIds }
            
            if (missingPlatforms.isNotEmpty() || existing.isEmpty()) {
                val newSettings = existing.toMutableList()
                missingPlatforms.forEachIndexed { idx, platform ->
                    newSettings.add(
                        PlatformSettingEntity(
                            id = platform.id,
                            name = platform.name,
                            isVisible = true,
                            isPinned = false,
                            sortOrder = existing.size + idx
                        )
                    )
                }
                dao.savePlatformSettings(newSettings)
            }
        }

        // Initial fetch for the default selected platform "wb"
        setPlatform("wb")
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    fun setPlatform(platform: String) {
        _selectedPlatform.value = platform
        _errorMessage.value = null
        refresh()
    }

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun setCardSize(size: String) {
        _cardSize.value = size
        sharedPrefs.edit().putString("card_size", size).apply()
    }

    // Settings adjustments hooks
    fun togglePlatformVisibility(platformId: String) {
        viewModelScope.launch {
            val dao = database.hotTopicDao()
            val list = dao.getPlatformSettingsOnce().map {
                if (it.id == platformId) it.copy(isVisible = !it.isVisible) else it
            }
            dao.savePlatformSettings(list)
        }
    }

    fun togglePlatformPinned(platformId: String) {
        viewModelScope.launch {
            val dao = database.hotTopicDao()
            val list = dao.getPlatformSettingsOnce().map {
                if (it.id == platformId) it.copy(isPinned = !it.isPinned) else it
            }
            dao.savePlatformSettings(list)
            
            // Adjust current platform if the selected one is hidden or pinned
            val settings = dao.getPlatformSettingsOnce()
            val currentVisible = settings.filter { it.isVisible }
            if (currentVisible.isNotEmpty() && currentVisible.none { it.id == _selectedPlatform.value }) {
                _selectedPlatform.value = currentVisible.first().id
            }
        }
    }

    fun movePlatformUp(platformId: String) {
        viewModelScope.launch {
            val dao = database.hotTopicDao()
            val settings = dao.getPlatformSettingsOnce().toMutableList()
            val index = settings.indexOfFirst { it.id == platformId }
            if (index > 0) {
                val item = settings[index]
                val prev = settings[index - 1]
                val tempOrder = prev.sortOrder
                settings[index] = item.copy(sortOrder = tempOrder)
                settings[index - 1] = prev.copy(sortOrder = item.sortOrder)
                dao.savePlatformSettings(settings)
            }
        }
    }

    fun movePlatformDown(platformId: String) {
        viewModelScope.launch {
            val dao = database.hotTopicDao()
            val settings = dao.getPlatformSettingsOnce().toMutableList()
            val index = settings.indexOfFirst { it.id == platformId }
            if (index in 0 until settings.size - 1) {
                val item = settings[index]
                val next = settings[index + 1]
                val tempOrder = next.sortOrder
                settings[index] = item.copy(sortOrder = tempOrder)
                settings[index + 1] = next.copy(sortOrder = item.sortOrder)
                dao.savePlatformSettings(settings)
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                repository.fetchAndStoreHotTopics(_selectedPlatform.value)
            } catch (e: Exception) {
                val detail = e.localizedMessage ?: e.message ?: "连接超时"
                _errorMessage.value = "网络异常：数据加载失败 ($detail)，已为您加载本地缓存。"
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setShowDashboard(show: Boolean) {
        _showDashboard.value = show
    }

    private var testJob: kotlinx.coroutines.Job? = null

    fun runSpeedTest() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _isTestingAll.value = true
            
            // Initialise map state to TESTING for all platforms
            val initialMap = fullPlatformList.associate { info ->
                info.id to DiagnosticResult(info.id, "TESTING")
            }
            _diagnosticList.value = initialMap

            // Parallel testing across all 16 channels
            val jobs = fullPlatformList.map { platform ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()
                    try {
                        val list = repository.testPlatformConnection(platform.id)
                        val latency = System.currentTimeMillis() - startTime
                        val count = list.size
                        val updateTime = if (list.isNotEmpty()) list.first().updateTime else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        val topTopic = if (list.isNotEmpty()) list.first().title else "-暂无核心热点-"
                        
                        repository.storeCustomList(platform.id, list)

                        _diagnosticList.update { current ->
                            current + (platform.id to DiagnosticResult(
                                platformId = platform.id,
                                status = "SUCCESS",
                                latencyMs = latency,
                                count = count,
                                latestTime = updateTime,
                                top1Topic = topTopic
                            ))
                        }
                    } catch (e: Exception) {
                        val latency = System.currentTimeMillis() - startTime
                        val reason = e.localizedMessage ?: e.message ?: "连接超时/墙阻断"
                        
                        // Load cached snapshot data in case of failure so navigation is functional
                        val list = repository.loadOrGenerateFallback(platform.id)
                        val updateTime = if (list.isNotEmpty()) list.first().updateTime else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        val topTopic = if (list.isNotEmpty()) list.first().title else "-暂无核心热点-"
                        
                        _diagnosticList.update { current ->
                            current + (platform.id to DiagnosticResult(
                                platformId = platform.id,
                                status = "FAILED",
                                latencyMs = latency,
                                count = list.size,
                                latestTime = updateTime,
                                top1Topic = topTopic,
                                errorDetail = reason
                            ))
                        }
                    }
                }
            }

            jobs.forEach { it.join() }
            _isTestingAll.value = false
        }
    }
}
