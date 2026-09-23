package com.coomi.lifetrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.ColumnScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coomi.lifetrace.data.GeoJsonIO
import com.coomi.lifetrace.sync.WebDavSync
import com.coomi.lifetrace.tracking.LocationTrackingService
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一生足迹") },
                actions = {
                    IconButton(onClick = {
                        val sync = WebDavSync(context)
                        if (sync.isConfigured()) {
                            // 后台同步最近一段
                            val geojson = GeoJsonIO.exportGeoJson(points)
                            Toast.makeText(context, "正在同步…", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "请先配置 WebDAV（设置）", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.Cloud, contentDescription = "同步") }

                    IconButton(onClick = {
                        exportAll(context, points)
                    }) { Icon(Icons.Default.Share, contentDescription = "导出") }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                RangeButton("今天", QueryRange.DAY, currentRange) { currentRange = it; vm.load(it) }
                RangeButton("本周", QueryRange.WEEK, currentRange) { currentRange = it; vm.load(it) }
                RangeButton("本月", QueryRange.MONTH, currentRange) { currentRange = it; vm.load(it) }
                RangeButton("今年", QueryRange.YEAR, currentRange) { currentRange = it; vm.load(it) }
                RangeButton("一生", QueryRange.LIFE, currentRange) { currentRange = it; vm.load(it) }
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
                }
            ) {
                if (tracking) Icon(Icons.Default.Stop, contentDescription = "停止") else Icon(Icons.Default.PlayArrow, contentDescription = "开始")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MapViewCompose(points)
            Text(
                "${currentRange.label}：${points.size} 个点 | 总计 ${totalCount} 个点",
                Modifier.padding(8.dp)
            )
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
fun RangeButton(label: String, range: QueryRange, current: QueryRange, onSelect: (QueryRange) -> Unit) {
    TextButton(onClick = { onSelect(range) }) {
        Text(label, color = if (current == range) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
    }
}

@Composable
fun ColumnScope.MapViewCompose(points: List<com.coomi.lifetrace.data.TrackPoint>) {
    val context = LocalContext.current
    // MapView 在 factory 里创建（此时组件树已挂载，MapView 初始化完成），
    // 避免在 remember 初始化块中对尚未 attach 的 MapView 调用 setZoom/setCenter 触发潜在 NPE。
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }
    // 首次挂载后统一设置缩放与中心
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
        modifier = Modifier.fillMaxWidth().weight(1f),
        update = { mv ->
            mv.overlays.clear()
            if (points.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = android.graphics.Color.parseColor("#4CAF50")
                    outlinePaint.strokeWidth = 8f
                }
                mv.overlays.add(line)
                // 起点/终点标记
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
        } catch (e: Exception) {
            // 部分机型不支持直接跳转，忽略
        }
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
        title = { Text("省电暂停设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("规则命中时自动暂停记录以省电，离开后自动恢复。", style = MaterialTheme.typography.body2)

                // ---- 地点范围 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = zoneEnabled, onCheckedChange = { zoneEnabled = it })
                    Text("进入指定地点范围时暂停")
                }
                if (zoneEnabled) {
                    OutlinedTextField(value = zoneLat, onValueChange = { zoneLat = it },
                        label = { Text("纬度") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = zoneLon, onValueChange = { zoneLon = it },
                        label = { Text("经度") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = zoneRadius, onValueChange = { zoneRadius = it },
                        label = { Text("半径(米)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                // ---- WiFi ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wifiEnabled, onCheckedChange = { wifiEnabled = it })
                    Text("连接指定WiFi时暂停")
                }
                if (wifiEnabled) {
                    OutlinedTextField(value = wifiSsid, onValueChange = { wifiSsid = it },
                        label = { Text("WiFi SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
