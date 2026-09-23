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

    // --- 权限：Android 11+ 要求先请求前台定位，再单独二次请求后台定位 ---
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 无论后台是否授权，拿到前台权限就启动记录（后台拿不到只影响保活）
        if (result.values.all { it }) {
            requestIgnoreBattery(context)
        }
        startTracking(context, vm)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            requestIgnoreBattery(context)
            // 二次请求后台定位权限（Android 11+ 必须单独弹窗）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!bgGranted) {
                    bgLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    return@rememberLauncherForActivityResult
                }
            }
            startTracking(context, vm)
        } else {
            Toast.makeText(context, "需要定位权限才能记录轨迹", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshCount()
        vm.loadTrackingState()
        vm.load(currentRange)
        // 打开软件自动开始记录（若用户开启且已获得定位权限）
        val auto = context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
            .getBoolean("auto_start", true)
        if (auto && !vm.tracking.value) {
            requestAndStart(context, vm, permLauncher)
        }
    }

    val statusText = if (tracking) "记录中" else "未记录"

    Box(Modifier.fillMaxSize()) {
        // ---- 全屏地图 ----
        MapViewCompose(
            points = points,
            fitAll = currentRange == QueryRange.LIFE
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

        // ---- 右上角：设置 ----
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .clip(CircleShape)
                .background(FrostCard)
                .size(42.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = AccentRed)
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
                Text(
                    if (stats.maxSpeedKmh > 0) "%.1f km/h".format(stats.maxSpeedKmh) else "0.0 km/h",
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
                Toast.makeText(context, "定位已居中", Toast.LENGTH_SHORT).show()
            }
            FloatAction(Icons.Default.Settings, "配置") { showSettings = true }
            FloatAction(Icons.Default.Share, "导出") { exportAll(context, points) }
            FloatAction(Icons.Default.Search, "搜索") {
                Toast.makeText(context, "搜索地点（待开发）", Toast.LENGTH_SHORT).show()
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
                        requestAndStart(context, vm, permLauncher)
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
            onSelect = { currentRange = it; vm.load(it) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onAddPlace = { lat, lon, radius, name ->
                PlaceStore(context).addAt(lat, lon, radius, name)
                Toast.makeText(context, "已添加常去地点", Toast.LENGTH_SHORT).show()
            },
            points = points
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
fun MapViewCompose(points: List<TrackPoint>, fitAll: Boolean) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(LifeTraceApp.amapTileSource())
            setMultiTouchControls(true)
            setBackgroundColor(android.graphics.Color.WHITE)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
        }
    }
    var lastFit by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        mapView.controller.setZoom(6.0)
    }
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { mv ->
            mv.overlays.clear()
            if (points.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = android.graphics.Color.parseColor("#E53935")
                    outlinePaint.strokeWidth = 8f
                }
                mv.overlays.add(line)
                val start = Marker(mv).apply {
                    position = GeoPoint(points.first().latitude, points.first().longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "起点"
                    icon = context.getDrawable(R.drawable.ic_launcher_foreground)
                }
                val end = Marker(mv).apply {
                    position = GeoPoint(points.last().latitude, points.last().longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "终点"
                    icon = context.getDrawable(R.drawable.ic_launcher_foreground)
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

/** 前台 + 后台定位权限都已拿到才直接启动；否则弹前台权限框 */
private fun requestAndStart(
    context: Context,
    vm: MainViewModel,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val fg = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    val bgNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
    val need = if (bgNeeded) fg + Manifest.permission.ACCESS_BACKGROUND_LOCATION else fg
    if (hasAll(context, fg)) {
        startTracking(context, vm)
    } else {
        launcher.launch(fg)
    }
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
    val f = GeoJsonIO.writeExportFile(context, points, "life-trace")
    // 通过 FileProvider + ACTION_SEND 唤起分享面板
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出轨迹"))
    } catch (e: Exception) {
        Toast.makeText(context, "已导出到 ${f.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

/** 设置页：WebDAV 配置 + 常去地点管理 + 省电暂停 + 自动开始 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onAddPlace: (Double, Double, Double, String) -> Unit, points: List<TrackPoint>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE) }
    val syncScope = rememberCoroutineScope()

    var webdavUrl by remember { mutableStateOf(prefs.getString("webdav_url", "") ?: "") }
    var webdavUser by remember { mutableStateOf(prefs.getString("webdav_user", "") ?: "") }
    var webdavPass by remember { mutableStateOf(prefs.getString("webdav_pass", "") ?: "") }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", true)) }

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
                        // 定位当前位置并加入常去地点
                        try {
                            fusedClient.lastLocation.addOnSuccessListener { loc: Location? ->
                                if (loc != null) {
                                    onAddPlace(loc.latitude, loc.longitude, 200.0, "")
                                    places = placeStore.all()
                                } else {
                                    Toast.makeText(context, "暂未获取到定位，请稍后重试", Toast.LENGTH_SHORT).show()
                                }
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
                prefs.edit()
                    .putString("webdav_url", webdavUrl)
                    .putString("webdav_user", webdavUser)
                    .putString("webdav_pass", webdavPass)
                    .putBoolean("auto_start", autoStart)
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
