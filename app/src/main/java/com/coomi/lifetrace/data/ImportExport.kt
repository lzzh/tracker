package com.coomi.lifetrace.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹导入导出：CSV 与 GPX 两种通用格式。
 *
 * - CSV: 时间,纬度,经度,海拔,精度,速度,方位  （纯文本，Excel/表格可直接打开）
 * - GPX: 标准 GPS 交换格式，可导入到绝大多数地图/运动软件
 */
object ImportExport {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    // ---------- 导出 ----------

    fun exportCsv(points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("time,latitude,longitude,altitude,accuracy,speed,bearing\n")
        for (p in points) {
            sb.append(p.timestamp).append(',')
              .append(p.latitude).append(',')
              .append(p.longitude).append(',')
              .append(p.altitude).append(',')
              .append(p.accuracy).append(',')
              .append(p.speed).append(',')
              .append(p.bearing).append('\n')
        }
        return sb.toString()
    }

    fun exportGpx(points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"LifeTrace\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        if (points.isNotEmpty()) {
            sb.append("  <trk>\n")
            sb.append("    <name>LifeTrace ${points.size} points</name>\n")
            sb.append("    <trkseg>\n")
            for (p in points) {
                sb.append("      <trkpt lat=\"").append(p.latitude).append("\" lon=\"").append(p.longitude).append("\">\n")
                sb.append("        <ele>").append(p.altitude).append("</ele>\n")
                sb.append("        <time>").append(isoFmt.format(Date(p.timestamp))).append("</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** 把指定格式写入导出目录，返回文件 */
    fun writeExportFile(context: Context, points: List<TrackPoint>, prefix: String, ext: String, content: String): File {
        val dir = File(context.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$prefix-${System.currentTimeMillis()}.$ext")
        f.writeText(content)
        return f
    }

    // ---------- 导入 ----------

    /**
     * 解析导入文件（自动识别 CSV / GPX / GeoJSON）。
     * 返回解析出的轨迹点列表；解析失败返回空列表。
     */
    fun importFromFile(file: File): List<TrackPoint> {
        val text = file.readText()
        return when {
            text.trimStart().startsWith("<?xml") || text.contains("<gpx") -> parseGpx(text)
            text.trimStart().startsWith("{") -> parseGeoJson(text)
            else -> parseCsv(text)
        }
    }

    /** CSV: time,latitude,longitude,altitude,accuracy,speed,bearing */
    private fun parseCsv(text: String): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        val lines = text.lines()
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            // 跳过表头（含非数字的首字段）
            val parts = line.split(",")
            if (parts.size < 3) continue
            val ts = parts[0].trim().toLongOrNull() ?: continue
            val lat = parts[1].trim().toDoubleOrNull() ?: continue
            val lon = parts[2].trim().toDoubleOrNull() ?: continue
            if (i == 0 && parts[0].trim().equals("time", true)) continue
            out.add(
                TrackPoint(
                    timestamp = ts,
                    latitude = lat,
                    longitude = lon,
                    altitude = parts.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0,
                    accuracy = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0f,
                    speed = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0f,
                    bearing = parts.getOrNull(6)?.trim()?.toFloatOrNull() ?: 0f
                )
            )
        }
        return out
    }

    /** GPX: 提取所有 trkpt 的 lat/lon/ele/time */
    private fun parseGpx(text: String): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        val ptRegex = Regex("<trkpt[^>]*lat=\"([-0-9.]+)\"[^>]*lon=\"([-0-9.]+)\"[^>]*>([\\s\\S]*?)</trkpt>")
        for (m in ptRegex.findAll(text)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val inner = m.groupValues[3]
            val ele = Regex("<ele>([-0-9.]+)</ele>").find(inner)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val timeStr = Regex("<time>([^<]+)</time>").find(inner)?.groupValues?.get(1)
            val ts = try {
                if (timeStr != null) isoFmt.parse(timeStr)?.time ?: System.currentTimeMillis()
                else System.currentTimeMillis()
            } catch (e: Exception) { System.currentTimeMillis() }
            out.add(TrackPoint(timestamp = ts, latitude = lat, longitude = lon, altitude = ele))
        }
        return out
    }

    /** GeoJSON: 支持 Point 坐标数组（本应用导出格式） */
    private fun parseGeoJson(text: String): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        // 匹配 "coordinates": [lon, lat] 或 [lon, lat, ele]
        val coordRegex = Regex("\"coordinates\"\\s*:\\s*\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)(?:\\s*,\\s*([-0-9.]+))?\\s*\\]")
        val timeRegex = Regex("\"time\"\\s*:\\s*(\\d+)")
        val times = timeRegex.findAll(text).map { it.groupValues[1].toLong() }.toList()
        val coords = coordRegex.findAll(text).toList()
        for ((i, m) in coords.withIndex()) {
            val lon = m.groupValues[1].toDoubleOrNull() ?: continue
            val lat = m.groupValues[2].toDoubleOrNull() ?: continue
            val ele = m.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            val ts = times.getOrNull(i) ?: System.currentTimeMillis()
            out.add(TrackPoint(timestamp = ts, latitude = lat, longitude = lon, altitude = ele))
        }
        return out
    }
}
