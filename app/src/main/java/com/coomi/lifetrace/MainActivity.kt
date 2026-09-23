package com.coomi.lifetrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.coomi.lifetrace.data.GeoJsonIO
import com.coomi.lifetrace.sync.WebDavSync
import com.coomi.lifetrace.tracking.LocationTrackingService
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker

// ---- 主题配色 ----
val PrimaryGreen = Color(0xFF2E7D32)
val AccentGreen = Color(0xFF66BB6A)
val SurfaceBg = Color(0xFFF4F6F4)
val DarkText = Color(0xFF1B2A1B)

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
            primary = PrimaryGreen,
            primaryVariant = AccentGreen,
            secondary = AccentGreen,
            background = SurfaceBg,
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = DarkText,
            onSurface = DarkText
        )
    ) { content() }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val points by vm.points.collectAsState()
    val tracking by vm.tracking.collectAsState()
    val totalCount by vm.totalCount.collectAsState()
    var currentRange by remember { mutableStateOf<QueryRange>(QueryRange.LIFE) }
    var showSettings by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            requestIgnoreBattery(context)
            LocationTrackingService.start(context)
        } else {
            Toast.makeText(context, "需要定位权限才能记录轨迹", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshCount()
        vm.loadTrackingState()
        vm.load(currentRange)
    }

    // 顶部状态文案
    val statusText = if (tracking) "正在记录轨迹" else "未在记录"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("一生足迹", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(statusText, fontSize = 12.sp, color = Color(0xCCFFFFFF))
                    }
                },
                backgroundColor = PrimaryGreen,
                contentColor = Color.White,
                elevation = 4.dp,
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = {
                        val sync = WebDavSync(context)
                        if (sync.isConfigured()) {
                            Toast.makeText(context, "正在同步…", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "请先配置 WebDAV（设置）", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.Cloud, contentDescription = "同步") }
                    IconButton(onClick = { exportAll(context, points) }) {
                        Icon(Icons.Default.Share, contentDescription = "导出")
                    }
                }
            )
        },
        bottomBar = {
            Surface(elevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RangeButton("今天", QueryRange.DAY, currentRange) { currentRange = it; vm.load(it) }
                    RangeButton("本周", QueryRange.WEEK, currentRange) { currentRange = it; vm.load(it) }
                    RangeButton("本月", QueryRange.MONTH, currentRange) { currentRange = it; vm.load(it) }
                    RangeButton("今年", QueryRange.YEAR, currentRange) { currentRange = it; vm.load(it) }
                    RangeButton("一生", QueryRange.LIFE, currentRange) { currentRange = it; vm.load(it) }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val allPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    else arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (hasAll(context, allPerms)) {
                        if (tracking) {
                            LocationTrackingService.stop(context)
                            vm.setTracking(false)
                            Toast.makeText(context, "已停止记录", Toast.LENGTH_SHORT).show()
                        } else {
                            requestIgnoreBattery(context)
                            LocationTrackingService.start(context)
                            vm.setTracking(true)
                            Toast.makeText(context, "开始记录轨迹", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        permLauncher.launch(allPerms)
                    }
                },
                backgroundColor = if (tracking) Color(0xFFC62828) else PrimaryGreen,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp, pressedElevation = 6.dp
                )
            ) {
                Icon(
                    if (tracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (tracking) "停止" else "开始",
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        backgroundColor = SurfaceBg
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 地图卡片
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    MapViewCompose(points)
                    // 左上角数据卡片
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        color = Color(0xCCFFFFFF),
                        shape = RoundedCornerShape(10.dp),
                        elevation = 2.dp
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("${currentRange.label}轨迹", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PrimaryGreen)
                            Text("${points.size} 个点 · 累计 ${totalCount} 点", fontSize = 12.sp, color = DarkText)
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
fun RangeButton(label: String, range: QueryRange, current: QueryRange, onSelect: (QueryRange) -> Unit) {
    val selected = current == range
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) PrimaryGreen else Color.Transparent,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onSelect(range) }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) Color.White else Color(0xFF555555),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

@Composable
fun MapViewCompose(points: List<com.coomi.lifetrace.data.TrackPoint>) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(LifeTraceApp.amapTileSource())
            setMultiTouchControls(true)
        }
    }
    LaunchedEffect(Unit) {
        if (points.isNotEmpty()) {
            mapView.controller.setZoom(14.0)
            mapView.controller.setCenter(GeoPoint(points.first().latitude, points.first().longitude))
        } else {
            mapView.controller.setZoom(6.0)
        }
        mapView.invalidate()
    }
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxWidth(),
        update = { mv ->
            mv.overlays.clear()
            if (points.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = android.graphics.Color.parseColor("#2E7D32")
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
                mv.invalidate()
            }
        }
    )
}

private fun hasAll(context: Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

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

private fun exportAll(context: Context, points: List<com.coomi.lifetrace.data.TrackPoint>) {
    if (points.isEmpty()) {
        Toast.makeText(context, "没有可导出的轨迹", Toast.LENGTH_SHORT).show()
        return
    }
    val f = GeoJsonIO.writeExportFile(context, points, "life-trace")
    Toast.makeText(context, "已导出到 ${f.absolutePath}", Toast.LENGTH_LONG).show()
}

/** 智能暂停设置：静止 / 地点范围 / WiFi */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE) }

    var zoneEnabled by remember { mutableStateOf(prefs.getBoolean("pause_zone_enabled", false)) }
    var zoneLat by remember { mutableStateOf(prefs.getFloat("pause_zone_lat", 0f).toString()) }
    var zoneLon by remember { mutableStateOf(prefs.getFloat("pause_zone_lon", 0f).toString()) }
    var zoneRadius by remember { mutableStateOf(prefs.getFloat("pause_zone_radius", 200f).toString()) }
    var wifiEnabled by remember { mutableStateOf(prefs.getBoolean("pause_wifi_enabled", false)) }
    var wifiSsid by remember { mutableStateOf(prefs.getString("pause_wifi_ssid", "") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("省电暂停设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("规则命中时自动暂停记录以省电，离开后自动恢复。", style = MaterialTheme.typography.body2, color = Color(0xFF666666))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { zoneEnabled = !zoneEnabled }) {
                    Checkbox(checked = zoneEnabled, onCheckedChange = { zoneEnabled = it })
                    Text("进入指定地点范围时暂停")
                }
                if (zoneEnabled) {
                    OutlinedTextField(value = zoneLat, onValueChange = { zoneLat = it }, label = { Text("纬度") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = zoneLon, onValueChange = { zoneLon = it }, label = { Text("经度") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = zoneRadius, onValueChange = { zoneRadius = it }, label = { Text("半径(米)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
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
                    .putBoolean("pause_zone_enabled", zoneEnabled)
                    .putFloat("pause_zone_lat", zoneLat.toFloatOrNull() ?: 0f)
                    .putFloat("pause_zone_lon", zoneLon.toFloatOrNull() ?: 0f)
                    .putFloat("pause_zone_radius", zoneRadius.toFloatOrNull() ?: 200f)
                    .putBoolean("pause_wifi_enabled", wifiEnabled)
                    .putString("pause_wifi_ssid", wifiSsid)
                    .apply()
                Toast.makeText(context, "已保存省电设置", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("保存", color = PrimaryGreen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
