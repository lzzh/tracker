package com.coomi.lifetrace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.coomi.lifetrace.data.GeoJsonIO
import com.coomi.lifetrace.data.PlaceStore
import com.coomi.lifetrace.data.TrackPoint
import com.coomi.lifetrace.sync.WebDavSync
import com.coomi.lifetrace.tracking.LocationTrackingService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Calendar
import android.app.DatePickerDialog

// ---- 主题配色（参考"一生足迹"暗色地图风）----
val AccentRed = Color(0xFFE53935)
val DarkBoard = Color(0xFF1A1A1A)
val FrostCard = Color(0xEEFFFFFF)
val BottomBarBg = Color(0xF7FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeTraceTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun LifeTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = lightColors(
            primary = AccentRed,
            primaryVariant = AccentRed,
            secondary = AccentRed,
            background = DarkBoard,
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = DarkBoard,
            onSurface = DarkBoard
        )
    ) { content() }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel()
    val points by vm.points.collectAsState()
    val tracking by vm.tracking.collectAsState()
    val totalCount by vm.totalCount.collectAsState()
    val stats by vm.stats.collectAsState()
    var currentRange by remember { mutableStateOf<QueryRange>(QueryRange.DAY) }
    var showSettings by remember { mutableStateOf(false) }
    var tileKey by remember { mutableStateOf(context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE).getString("map_tile", "osm") ?: "osm") }
    // 地图控制器引用，供「定位」按钮将视角移到当前位置
    val mapRef = remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    // --- 完整权限流：前台定位 →(11+)后台定位 →(13+)通知 → 忽略电池优化 → 启动 ---
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 通知权限回来（13+），继续检查是否能启动
        if (granted) requestIgnoreBattery(context)
        // 通知权限是可选增强，无论授予与否都尝试启动（后台保活主要靠前台定位权限）
        startTrackingIfReady(context, vm)
    }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 后台定位回来，继续请求通知权限（13+）
        if (result.values.all { it }) requestIgnoreBattery(context)
        requestNotifIfNeeded(context, vm, notifLauncher)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "需要定位权限才能记录轨迹", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        requestIgnoreBattery(context)
        // 前台定位已拿到，Android 11+ 单独二次请求后台定位
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            bgLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            requestNotifIfNeeded(context, vm, notifLauncher)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshCount()
        vm.loadTrackingState()
        vm.load(currentRange)
        // 打开软件自动开始记录：完整检查权限链，缺哪个弹哪个，全部就绪才启动
        val auto = context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
            .getBoolean("auto_start", true)
        if (auto && !vm.tracking.value) {
            requestTrackingChain(context, vm, permLauncher, bgLauncher, notifLauncher)
        }
    }

    val statusText = if (tracking) "记录中" else "未记录"

    Box(Modifier.fillMaxSize()) {
        // ---- 全屏地图 ----
        MapViewCompose(
            points = points,
            fitAll = currentRange == QueryRange.LIFE,
            tileKey = tileKey,
            mapRef = mapRef
        )

        // ---- 顶部状态悬浮卡（参考图：左上角 logo + 状态）----
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FrostCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(AccentRed),
                contentAlignment = Alignment.Center
            ) {
                Text("足", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("一生足迹", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DarkBoard)
                Text("普通模式 GPS ${if (tracking) "正常" else "已停"}", fontSize = 11.sp, color = AccentRed)
            }
        }


        // ---- 左侧中部：海拔/速度卡 ----
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(FrostCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("海拔/速度", fontSize = 11.sp, color = Color(0xFF666666))
                // 用最新一个轨迹点的速度（实时随记录变化），避免恒为固定值
                val lastSpeed = points.lastOrNull()?.speed?.times(3.6) ?: 0.0
                Text(
                    "%.1f km/h".format(lastSpeed),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentRed
                )
            }
        }

        // ---- 右侧垂直悬浮按钮组 ----
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(FrostCard)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FloatAction(Icons.Default.MyLocation, "定位") {
                locateToCurrent(context, mapRef)
            }
            FloatAction(Icons.Default.Settings, "配置") { showSettings = true }
            FloatAction(Icons.Default.Share, "导出") { exportAll(context, points) }
            FloatAction(Icons.Default.Search, "搜索") {
                showSearch = true
            }
            FloatAction(Icons.Default.Details, "详细") {
                Toast.makeText(context, "当前共 ${points.size} 个轨迹点", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- 左下浮动统计卡（参考图红字统计）----
        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 84.dp),
            elevation = 6.dp,
            shape = RoundedCornerShape(14.dp),
            backgroundColor = FrostCard
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    "已记录 ${points.size} 个轨迹点", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, color = DarkBoard
                )
                Text(
                    "总距离 %.2f 千米".format(stats.distanceM / 1000.0),
                    fontSize = 12.sp, color = AccentRed
                )
                Text(
                    "平均速度 %.1f km/h，最高速度 %.1f km/h".format(stats.avgSpeedKmh, stats.maxSpeedKmh),
                    fontSize = 12.sp, color = DarkBoard
                )
            }
        }

        // ---- 右上：开始/停止记录按钮 ----
        Button(
                onClick = {
                    if (tracking) {
                        LocationTrackingService.stop(context)
                        vm.setTracking(false)
                        Toast.makeText(context, "已停止记录", Toast.LENGTH_SHORT).show()
                    } else {
                        requestTrackingChain(context, vm, permLauncher, bgLauncher, notifLauncher)
                        Toast.makeText(context, "开始记录轨迹", Toast.LENGTH_SHORT).show()
                    }
                },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (tracking) Color(0xFFC62828) else Color(0xFF05C755),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        ) {
            Icon(
                if (tracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(if (tracking) "停止" else "开始", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // ---- 底部日期选择栏（今日/昨日/自定义）----
        BottomRangeBar(
            context = context,
            vm = vm,
            current = currentRange,
            onSelect = { range ->
                if (range == QueryRange.CUSTOM) {
                    currentRange = range
                    showCustomDatePicker(context, vm)
                } else {
                    currentRange = range; vm.load(range)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showSearch) {
        SearchDialog(
            onDismiss = { showSearch = false },
            onResult = { lat, lon, name ->
                val mv = mapRef.value
                if (mv != null) {
                    mv.controller.animateTo(GeoPoint(lat, lon))
                    mv.controller.setZoom(14.0)
                }
                Toast.makeText(context, "已定位到：", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onAddPlace = { lat, lon, radius, name ->
                PlaceStore(context).addAt(lat, lon, radius, name)
                Toast.makeText(context, "已添加常去地点", Toast.LENGTH_SHORT).show()
            },
            points = points,
            onTileChange = { key -> tileKey = key }
        )
    }
}

@Composable
fun FloatAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = AccentRed, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 9.sp, color = Color(0xFF666666), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BottomRangeBar(
    context: Context,
    vm: MainViewModel,
    current: QueryRange,
    onSelect: (QueryRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), elevation = 10.dp, color = BottomBarBg) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf("今天" to QueryRange.DAY, "昨天" to QueryRange.YESTERDAY, "自定义" to QueryRange.CUSTOM)
                tabs.forEach { (label, range) ->
                    val selected = current == range
                    Text(
                        label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelect(range) }
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                            .background(if (selected) AccentRed else Color.Transparent, RoundedCornerShape(20.dp)),
                        color = if (selected) Color.White else Color(0xFF555555),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MapViewCompose(points: List<TrackPoint>, fitAll: Boolean, tileKey: String, mapRef: androidx.compose.runtime.MutableState<org.osmdroid.views.MapView?>) {
    val context = LocalContext.current
    val mapView = remember(tileKey) {
        MapView(context).apply {
            setTileSource(
                when (tileKey) {
                    "amap" -> LifeTraceApp.amapTileSource()
                    "osm_official" -> LifeTraceApp.osmTileSource()
                    "osm_de" -> LifeTraceApp.osmDeTileSource()
                    else -> LifeTraceApp.osmFrTileSource()   // 默认：法国 OSM 镜像（实测海外可用）
                }
            )
            setMultiTouchControls(true)
            setBackgroundColor(android.graphics.Color.WHITE)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
        }
    }
    var lastFit by remember { mutableStateOf(0) }
    LaunchedEffect(tileKey) {
        mapView.controller.setZoom(6.0)
    }
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { mv ->
            mapRef.value = mv
            mv.overlays.clear()
            if (points.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = android.graphics.Color.parseColor("#E53935")
                    outlinePaint.strokeWidth = 8f
                }
                mv.overlays.add(line)
                // 起点/终点用小红点标记，避免启动图标过大
                val start = Marker(mv).apply {
                    position = GeoPoint(points.first().latitude, points.first().longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "起点"
                    icon = context.getDrawable(R.drawable.ic_marker_point)
                }
                val end = Marker(mv).apply {
                    position = GeoPoint(points.last().latitude, points.last().longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "终点"
                    icon = context.getDrawable(R.drawable.ic_marker_point)
                }
                mv.overlays.add(start)
                mv.overlays.add(end)
                // 缩放适应轨迹（只在数据量变化时）
                if (points.size != lastFit) {
                    val bounds = org.osmdroid.util.BoundingBox.fromGeoPoints(
                        points.map { GeoPoint(it.latitude, it.longitude) }
                    )
                    mv.zoomToBoundingBox(bounds, true, 40)
                    lastFit = points.size
                }
            }
            mv.invalidate()
        }
    )
}

private fun hasAll(context: Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * 启动轨迹所需的完整权限链检查：缺哪个就弹哪个（Android 11+ 后台定位须单独弹），
 * 全部就绪才真正 startTracking。
 */
private fun requestTrackingChain(
    context: Context,
    vm: MainViewModel,
    fgLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    bgLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    notifLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val fg = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    // 1) 前台定位未授予 → 弹前台定位
    if (!hasAll(context, fg)) {
        fgLauncher.launch(fg)
        return
    }
    // 2) 前台已授予，Android 11+ 后台定位未授予 → 单独弹后台定位
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) {
        bgLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        return
    }
    // 3) 定位都齐了，Android 13+ 通知权限未授予 → 弹通知
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return
    }
    // 4) 全部就绪 → 忽略电池优化 + 启动
    requestIgnoreBattery(context)
    startTracking(context, vm)
}

/** Android 13+ 通知权限未授予且需要时弹窗（定位已齐），否则直接启动 */
private fun requestNotifIfNeeded(
    context: Context,
    vm: MainViewModel,
    notifLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        requestIgnoreBattery(context)
        startTracking(context, vm)
    }
}

/** 后台定位/通知都处理完后，若定位权限齐了就启动 */
private fun startTrackingIfReady(context: Context, vm: MainViewModel) {
    val fg = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (!hasAll(context, fg)) {
        Toast.makeText(context, "定位权限未授予，无法记录轨迹", Toast.LENGTH_LONG).show()
        return
    }
    requestIgnoreBattery(context)
    startTracking(context, vm)
}

/** 真正启动轨迹服务并更新状态 */
private fun startTracking(context: Context, vm: MainViewModel) {
    requestIgnoreBattery(context)
    LocationTrackingService.start(context)
    vm.setTracking(true)
}

private fun requestIgnoreBattery(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + context.packageName)
            context.startActivity(intent)
        } catch (e: Exception) { }
    }
}

private fun exportAll(context: Context, points: List<TrackPoint>) {
    if (points.isEmpty()) {
        Toast.makeText(context, "没有可导出的轨迹", Toast.LENGTH_SHORT).show()
        return
    }
    // 弹格式选择（CSV / GPX / GeoJSON）
    val items = arrayOf("CSV（表格）", "GPX（地图/运动软件）", "GeoJSON")
    android.app.AlertDialog.Builder(context)
        .setTitle("导出格式（${points.size} 个点）")
        .setItems(items) { _, which ->
            val (ext, content, mime) = when (which) {
                0 -> Triple("csv", com.coomi.lifetrace.data.ImportExport.exportCsv(points), "text/csv")
                1 -> Triple("gpx", com.coomi.lifetrace.data.ImportExport.exportGpx(points), "application/gpx+xml")
                else -> Triple("geojson", com.coomi.lifetrace.data.GeoJsonIO.exportGeoJson(points), "application/json")
            }
            val f = com.coomi.lifetrace.data.ImportExport.writeExportFile(context, points, "life-trace", ext, content)
            shareFile(context, f, mime)
        }
        .show()
}

/** 通过 FileProvider + ACTION_SEND 分享文件 */
private fun shareFile(context: Context, f: java.io.File, mime: String) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出轨迹"))
    } catch (e: Exception) {
        Toast.makeText(context, "已导出到 ${f.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

/** 从 Uri 读取文件并导入轨迹（CSV/GPX/GeoJSON 自动识别），返回导入条数 */
private suspend fun importFromUri(context: Context, uri: android.net.Uri): Int {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val temp = java.io.File(context.cacheDir, "import_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext 0
            val pts = com.coomi.lifetrace.data.ImportExport.importFromFile(temp)
            temp.delete()
            if (pts.isEmpty()) return@withContext 0
            // 直接写库
            val db = com.coomi.lifetrace.data.AppDatabase.get(context.applicationContext)
            for (p in pts) db.trackDao().insert(p)
            pts.size
        } catch (e: Exception) {
            0
        }
    }
}

/** 设置页：WebDAV 配置 + 常去地点管理 + 省电暂停 + 自动开始 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onAddPlace: (Double, Double, Double, String) -> Unit, points: List<TrackPoint>, onTileChange: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE) }
    val syncScope = rememberCoroutineScope()

    // 导入文件选择器：选 CSV/GPX/GeoJSON 文件导入轨迹
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        syncScope.launch {
            val n = importFromUri(context, uri)
            val msg = if (n > 0) "已导入 $n 个轨迹点" else "导入失败或文件为空"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    var webdavUrl by remember { mutableStateOf(prefs.getString("webdav_url", "") ?: "") }
    var webdavUser by remember { mutableStateOf(prefs.getString("webdav_user", "") ?: "") }
    var webdavPass by remember { mutableStateOf(prefs.getString("webdav_pass", "") ?: "") }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", true)) }
    var mapTile by remember { mutableStateOf(prefs.getString("map_tile", "osm") ?: "osm") }

    var zoneEnabled by remember { mutableStateOf(prefs.getBoolean("pause_zone_enabled", false)) }
    val placeStore = remember { PlaceStore(context) }
    var places by remember { mutableStateOf(placeStore.all()) }
    var wifiEnabled by remember { mutableStateOf(prefs.getBoolean("pause_wifi_enabled", false)) }
    var wifiSsid by remember { mutableStateOf(prefs.getString("pause_wifi_ssid", "") ?: "") }

    // 添加常去地点：用当前定位
    var adding by remember { mutableStateOf(false) }
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ---- 通用 ----
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { autoStart = !autoStart }) {
                    Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
                    Text("打开软件自动开始记录")
                }
                Spacer(Modifier.height(10.dp))
                Text("地图源", fontWeight = FontWeight.Bold, color = AccentRed)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("osm" to "OSM法国", "osm_de" to "OSM德国", "osm_official" to "OSM官方", "amap" to "高德").forEach { (key, label) ->
                        val sel = mapTile == key
                        Text(
                            label,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { mapTile = key; onTileChange(key) }
                                .background(if (sel) AccentRed else Color(0xFFEEEEEE), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (sel) Color.White else Color(0xFF555555),
                            fontSize = 13.sp
                        )
                    }
                }

                // ---- WebDAV 同步 ----
                Spacer(Modifier.height(16.dp))
                Text("WebDAV 同步", fontWeight = FontWeight.Bold, color = AccentRed)
                OutlinedTextField(value = webdavUrl, onValueChange = { webdavUrl = it }, label = { Text("WebDAV 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = webdavUser, onValueChange = { webdavUser = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = webdavPass, onValueChange = { webdavPass = it }, label = { Text("密码/应用密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    prefs.edit()
                        .putString("webdav_url", webdavUrl)
                        .putString("webdav_user", webdavUser)
                        .putString("webdav_pass", webdavPass)
                        .apply()
                    if (points.isEmpty()) {
                        Toast.makeText(context, "当前没有轨迹可同步", Toast.LENGTH_SHORT).show()
                    } else {
                        val sync = WebDavSync(context)
                        if (!sync.isConfigured()) {
                            Toast.makeText(context, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
                        } else {
                            val appContext = context.applicationContext
                            syncScope.launch {
                                val ok = WebDavSync(appContext).syncRange(points, "life-trace")
                                val msg = if (ok) "同步成功" else "同步失败，请检查地址/账号/密码"
                                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("立即同步", color = AccentRed) }

                // ---- 数据导入 ----
                Spacer(Modifier.height(16.dp))
                Text("数据导入", fontWeight = FontWeight.Bold, color = AccentRed)
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("text/*", "application/json", "application/gpx+xml", "*/*"))
                }) { Text("从文件导入（CSV / GPX / GeoJSON）", color = AccentRed) }

                // ---- 常去地点 ----
                Spacer(Modifier.height(16.dp))
                Text("常去地点（省电暂停）", fontWeight = FontWeight.Bold, color = AccentRed)
                zoneEnabled.let { _ ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { zoneEnabled = !zoneEnabled }) {
                        Checkbox(checked = zoneEnabled, onCheckedChange = { zoneEnabled = it })
                        Text("进入常去地点时暂停记录")
                    }
                }
                places.forEachIndexed { i, p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}. ${p.name}（半径${p.radius.toInt()}m）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val cur = placeStore.all().toMutableList()
                            if (i in cur.indices) { cur.removeAt(i); placeStore.save(cur); places = placeStore.all() }
                        }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = AccentRed, modifier = Modifier.size(18.dp)) }
                    }
                }
                if (!adding) {
                    TextButton(onClick = {
                        // 获取当前最新位置（getCurrentLocation 比 lastLocation 更可靠）并加入常去地点
                        try {
                            val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
                                .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                                .setDurationMillis(10000)
                                .build()
                            fusedClient.getCurrentLocation(req, null)
                                .addOnSuccessListener { loc: Location? ->
                                    if (loc != null) {
                                        onAddPlace(loc.latitude, loc.longitude, 200.0, "")
                                        places = placeStore.all()
                                        Toast.makeText(context, "已添加常去地点", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "暂未获取到定位，请稍后重试", Toast.LENGTH_SHORT).show()
                                    }
                                }.addOnFailureListener {
                                    Toast.makeText(context, "定位失败：${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        } catch (e: Exception) {
                            Toast.makeText(context, "定位失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("+ 添加当前地点", color = AccentRed) }
                }

                // ---- WiFi 暂停 ----
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { wifiEnabled = !wifiEnabled }) {
                    Checkbox(checked = wifiEnabled, onCheckedChange = { wifiEnabled = it })
                    Text("连接指定WiFi时暂停")
                }
                if (wifiEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = wifiSsid, onValueChange = { wifiSsid = it }, label = { Text("WiFi SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // WebDAV 地址校验：非空时须 http/https 开头，提示建议 https（密码明文传输风险）
                val url = webdavUrl.trim()
                if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(context, "WebDAV 地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                if (url.startsWith("http://")) {
                    Toast.makeText(context, "提示：http 明文传输密码不安全，建议使用 https", Toast.LENGTH_LONG).show()
                }
                prefs.edit()
                    .putString("webdav_url", url)
                    .putString("webdav_user", webdavUser)
                    .putString("webdav_pass", webdavPass)
                    .putBoolean("auto_start", autoStart)
                    .putString("map_tile", mapTile)
                    .putBoolean("pause_zone_enabled", zoneEnabled)
                    .putBoolean("pause_wifi_enabled", wifiEnabled)
                    .putString("pause_wifi_ssid", wifiSsid)
                    .apply()
                Toast.makeText(context, "已保存设置", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("保存", color = AccentRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 自定义日期：让用户依次选「起始日」「结束日」，然后据此筛选轨迹 */
private fun showCustomDatePicker(context: Context, vm: MainViewModel) {
    val cal = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, day ->
            val startCal = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            val startMs = startCal.timeInMillis
            val endDialog = DatePickerDialog(
                context,
                { _, ey, em, ed ->
                    val end = Calendar.getInstance().apply {
                        set(ey, em, ed, 23, 59, 59); set(Calendar.MILLISECOND, 0)
                    }
                    if (end.timeInMillis < startMs) {
                        Toast.makeText(context, "结束日期不能早于起始日期", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.loadCustom(startMs, end.timeInMillis)
                        Toast.makeText(context, "已按所选日期加载", Toast.LENGTH_SHORT).show()
                    }
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            endDialog.datePicker.minDate = startMs
            endDialog.show()
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@SuppressLint("MissingPermission")
private fun locateToCurrent(
    context: Context,
    mapRef: androidx.compose.runtime.MutableState<org.osmdroid.views.MapView?>
) {
    try {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        fused.lastLocation.addOnSuccessListener { loc: Location? ->
            val target = loc ?: return@addOnSuccessListener
            val mv = mapRef.value ?: return@addOnSuccessListener
            mv.controller.animateTo(GeoPoint(target.latitude, target.longitude))
            mv.controller.setZoom(16.0)
            Toast.makeText(context, "已定位到当前位置", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(context, "定位失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "定位异常：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 搜索对话框：输入地名，用 Nominatim(OpenStreetMap 海外可用) 解析坐标 */
@Composable
fun SearchDialog(onDismiss: () -> Unit, onResult: (Double, Double, String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索地点", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("输入地名（如：河内 / Ha Noi / 潍坊）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = AccentRed, fontSize = 12.sp)
                }
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    Text("正在搜索…", fontSize = 12.sp, color = Color(0xFF666666))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val q = query.trim()
                if (q.isBlank()) { error = "请输入地名"; return@TextButton }
                loading = true; error = ""
                scope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        searchPlace(q)
                    }
                    loading = false
                    if (r == null) {
                        error = "未找到该地点，请换个关键词"
                    } else {
                        onResult(r.first, r.second, r.third)
                        onDismiss()
                    }
                }
            }) { Text("搜索", color = AccentRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Nominatim 返回项（Gson 直接反序列化，避免正则解析不稳定） */
private data class NominatimPlace(val lat: String, val lon: String, val display_name: String)

/** 调用 Nominatim 搜索，返回 (lat, lon, displayName) 或 null */
private fun searchPlace(query: String): Triple<Double, Double, String>? {
    var conn: java.net.HttpURLConnection? = null
    return try {
        val url = java.net.URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                    java.net.URLEncoder.encode(query, "UTF-8")
        )
        conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "LifeTrace/1.0 (Android)")
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = com.google.gson.Gson().fromJson(
            body, Array<NominatimPlace>::class.java
        )
        val first = arr?.firstOrNull() ?: return null
        val lat = first.lat.toDoubleOrNull() ?: return null
        val lon = first.lon.toDoubleOrNull() ?: return null
        Triple(lat, lon, first.display_name.ifBlank { query })
    } catch (e: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}
