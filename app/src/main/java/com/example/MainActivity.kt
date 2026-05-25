package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.db.HotTopicEntity
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.HotTopicViewModel
import com.example.viewmodel.PlatformInfo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: HotTopicViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HotSearchScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun HotSearchScreen(viewModel: HotTopicViewModel) {
    HotSearchDetailScreen(viewModel = viewModel)
}

@Composable
fun HotSearchDetailScreen(viewModel: HotTopicViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val selectedPlatform by viewModel.selectedPlatform.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val hotTopics by viewModel.hotTopics.collectAsStateWithLifecycle()
    val cardSize by viewModel.cardSize.collectAsStateWithLifecycle()
    val visiblePlatforms by viewModel.visiblePlatforms.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Setup rotating animation for refresh indicator
    val rotationAnim = rememberInfiniteTransition()
    val rotateAngle by rotationAnim.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // Clear error message if showed via Toast and clean state
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearErrorMessage()
        }
    }

    // Auto switch to default if selected platform becomes invisible
    LaunchedEffect(visiblePlatforms) {
        if (visiblePlatforms.isNotEmpty() && visiblePlatforms.none { it.id == selectedPlatform }) {
            viewModel.setPlatform(visiblePlatforms.first().id)
        }
    }

    // Scroll to top when selected platform changes
    LaunchedEffect(selectedPlatform) {
        try {
            lazyListState.scrollToItem(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.refresh() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 16.dp, end = 16.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "一键刷新",
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(if (isRefreshing) rotateAngle else 0f)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // --- Custom Header / App Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlameCanvasIcon(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "今日热搜",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        // Dynamic caching metadata
                        val currentPlatformInfo = viewModel.fullPlatformList.firstOrNull { it.id == selectedPlatform }
                        val updateTimeStr = if (hotTopics.isNotEmpty()) {
                            hotTopics.first().updateTime
                        } else {
                            "点击刷新加载"
                        }
                        Text(
                            text = "${currentPlatformInfo?.name ?: ""} · 缓存于 $updateTimeStr",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Personalized Settings Gear Switch
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置偏好",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Modern styled Sun/Moon flat Toggle Switch
                    SunMoonToggle(
                        isDark = isDarkMode,
                        onToggle = { viewModel.toggleDarkMode() }
                    )
                }
            }

            // --- Horizontal Category Tabs ---
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visiblePlatforms, key = { it.id }) { platform ->
                    val isSelected = selectedPlatform == platform.id
                    PlatformPill(
                        platform = platform,
                        isSelected = isSelected,
                        onClick = {
                            viewModel.setPlatform(platform.id)
                        }
                    )
                }
            }

            // --- Main Content / Scrollable List ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                if (isRefreshing && hotTopics.isEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false
                    ) {
                        items(10) {
                            ShimmerHotTopicItem()
                        }
                    }
                } else if (hotTopics.isEmpty() && !isRefreshing) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "提示",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "暂无本地缓存数据",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "请点击下方刷新按钮或直接加载最新榜单",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                        Button(
                            onClick = { viewModel.refresh() },
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("立即加载最新趋势")
                        }
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(hotTopics) { topic ->
                            val currentPlatformInfo = viewModel.fullPlatformList.firstOrNull { it.id == selectedPlatform }
                            val platformColor = currentPlatformInfo?.accentColor ?: MaterialTheme.colorScheme.primary

                            HotTopicItem(
                                item = topic,
                                platformColor = platformColor,
                                cardSize = cardSize,
                                onItemClick = { item ->
                                    try {
                                        val urlToOpen = item.mobilUrl ?: item.url
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开连接，请检查是否安装浏览器", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                    
                    if (isRefreshing) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(30.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(30.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "榜单更新中...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Settings Overlay Dialog Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun SettingsDialog(
    viewModel: HotTopicViewModel,
    onDismiss: () -> Unit
) {
    val cardSize by viewModel.cardSize.collectAsStateWithLifecycle()
    val platformSettings by viewModel.platformSettings.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Settings Title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "个性化配置中心",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                Spacer(modifier = Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Size preference selection
                    item {
                        Column {
                            Text(
                                text = "内容卡片尺寸偏好",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "small" to "紧凑",
                                    "medium" to "标准",
                                    "large" to "宽松"
                                ).forEach { (key, label) ->
                                    val isSelected = cardSize == key
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clickable { viewModel.setCardSize(key) },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        ),
                                        border = BorderStroke(1.2.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Platform Filter and arrangements
                    item {
                        Text(
                            text = "源网站订阅、置顶与排序",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(platformSettings, key = { it.id }) { setting ->
                        val platformInfo = viewModel.fullPlatformList.firstOrNull { it.id == setting.id }
                        val brandColor = platformInfo?.accentColor ?: MaterialTheme.colorScheme.primary

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Checkbox(
                                        checked = setting.isVisible,
                                        onCheckedChange = { viewModel.togglePlatformVisibility(setting.id) },
                                        colors = CheckboxDefaults.colors(checkedColor = brandColor)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(brandColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = setting.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (setting.isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    // Pinned indicator flag star
                                    IconButton(
                                        onClick = { viewModel.togglePlatformPinned(setting.id) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "置顶",
                                            tint = if (setting.isPinned) Color(0xFFFFB800) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.movePlatformUp(setting.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = "向上越野",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.movePlatformDown(setting.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "向下排序",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("完成并保存 (Save Preferred)")
                }
            }
        }
    }
}

@Composable
fun PlatformPill(
    platform: PlatformInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val containerBg = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        if (isDark) Color(0xFFCAC4D0) else Color(0xFF49454F)
    }
    val borderStroke = if (isSelected) {
        BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.2.dp, if (isDark) Color(0xFF4A4458) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }

    Card(
        modifier = Modifier
            .height(38.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = borderStroke
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = platform.name,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}

@Composable
fun HotTopicItem(
    item: HotTopicEntity,
    platformColor: Color,
    cardSize: String,
    onItemClick: (HotTopicEntity) -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val paddingValues = when (cardSize) {
        "small" -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        "large" -> PaddingValues(horizontal = 18.dp, vertical = 16.dp)
        else -> PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }

    val rankFontSize = when (cardSize) {
        "small" -> 18.sp
        "large" -> 26.sp
        else -> 22.sp
    }

    val titleFontSize = when (cardSize) {
        "small" -> 14.sp
        "large" -> 16.sp
        else -> 15.sp
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = when (cardSize) { "small" -> 3.dp; "large" -> 8.dp; else -> 5.dp })
            .clickable { onItemClick(item) },
        shape = RoundedCornerShape(when (cardSize) { "small" -> 12.dp; "large" -> 20.dp; else -> 16.dp }),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank Number on Left
            Text(
                text = item.rank.toString(),
                fontSize = rankFontSize,
                fontWeight = FontWeight.Bold,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = when (item.rank) {
                    1 -> if (isDark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
                    2 -> if (isDark) Color(0xFFFFB4AB) else Color(0xFFD14343)
                    3 -> if (isDark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
                    else -> if (isDark) Color(0xFFCAC4D0).copy(alpha = 0.5f) else Color(0xFF49454F).copy(alpha = 0.5f)
                },
                modifier = Modifier.width(when (cardSize) { "small" -> 28.dp; else -> 36.dp }),
                textAlign = TextAlign.Start
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = when (cardSize) { "small" -> 18.sp; "large" -> 22.sp; else -> 20.sp }
                )

                if (cardSize != "small") {
                    Spacer(modifier = Modifier.height(4.dp))
                    val subText = if (!item.desc.isNullOrBlank()) {
                        "${item.desc} • 热度 ${item.hot ?: "详尽"}"
                    } else {
                        "热度 ${item.hot ?: "关注"}"
                    }
                    Text(
                        text = subText,
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF938F99) else Color(0xFF49454F),
                        maxLines = if (cardSize == "large") 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Status tag indicator
            val badgeBg: Color?
            val badgeText: Color?
            val badgeTextStr: String?

            if (item.rank == 1) {
                badgeBg = if (isDark) Color(0xFF8C1D18) else Color(0xFFF9DEDC)
                badgeText = if (isDark) Color(0xFFF9DEDC) else Color(0xFF8C1D18)
                badgeTextStr = "爆"
            } else if (item.rank == 2) {
                badgeBg = if (isDark) Color(0xFF4A4458) else Color(0xFFEADDFF)
                badgeText = if (isDark) Color(0xFFD0BCFF) else Color(0xFF4F378B)
                badgeTextStr = "热"
            } else if (item.rank == 3) {
                badgeBg = if (isDark) Color(0xFF4F378B) else Color(0xFFD0BCFF)
                badgeText = if (isDark) Color(0xFFEADDFF) else Color(0xFF381E72)
                badgeTextStr = "新"
            } else {
                badgeBg = null
                badgeText = null
                badgeTextStr = null
            }

            if (badgeTextStr != null && badgeBg != null && badgeText != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = badgeTextStr,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeText
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerHotTopicItem() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.LightGray.copy(alpha = 0.25f))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.LightGray.copy(alpha = 0.25f))
                )

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.LightGray.copy(alpha = 0.15f))
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.LightGray.copy(alpha = 0.2f))
            )
        }
    }
}

@Composable
fun FlameCanvasIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.05f)
            cubicTo(
                width * 0.68f, height * 0.25f,
                width * 0.88f, height * 0.45f,
                width * 0.84f, height * 0.66f
            )
            cubicTo(
                width * 0.8f, height * 0.9f,
                width * 0.2f, height * 0.9f,
                width * 0.16f, height * 0.66f
            )
            cubicTo(
                width * 0.12f, height * 0.45f,
                width * 0.32f, height * 0.25f,
                width * 0.5f, height * 0.05f
            )
            close()
        }
        drawPath(path = path, color = Color(0xFFFF483D))

        val innerPath = Path().apply {
            moveTo(width * 0.5f, height * 0.38f)
            cubicTo(
                width * 0.62f, height * 0.52f,
                width * 0.72f, height * 0.66f,
                width * 0.68f, height * 0.78f
            )
            cubicTo(
                width * 0.62f, height * 0.88f,
                width * 0.38f, height * 0.88f,
                width * 0.32f, height * 0.78f
            )
            cubicTo(
                width * 0.28f, height * 0.66f,
                width * 0.38f, height * 0.52f,
                width * 0.5f, height * 0.38f
            )
            close()
        }
        drawPath(path = innerPath, color = Color(0xFFFFB800))
    }
}

@Composable
fun SunMoonToggle(
    isDark: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(60.dp)
            .height(32.dp)
            .clickable(onClick = onToggle),
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF2B2930) else Color(0xFFE6E1E5)
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart
        ) {
            val offsetAnim by animateDpAsState(
                targetValue = if (isDark) 28.dp else 4.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            )

            Box(
                modifier = Modifier
                    .offset(x = offsetAnim)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFFCAC4D0) else Color(0xFFFFCC00))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isDark) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.8f, size.height * 0.1f)
                            cubicTo(
                                size.width * 0.2f, size.height * 0.2f,
                                size.width * 0.2f, size.height * 0.8f,
                                size.width * 0.8f, size.height * 0.9f
                            )
                            cubicTo(
                                size.width * 0.4f, size.height * 0.8f,
                                size.width * 0.4f, size.height * 0.2f,
                                size.width * 0.8f, size.height * 0.1f
                            )
                            close()
                         }
                        drawPath(path = path, color = Color(0xFF1C1B1F))
                    }
                } else {
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(color = Color(0xFFFF4500))
                    }
                }
            }
        }
    }
}






