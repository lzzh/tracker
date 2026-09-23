package com.coomi.lifetrace.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 轨迹导入导出：GeoJSON (通用、可被多数地图工具打开) */
object GeoJsonIO {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun exportGeoJson(points: List<TrackPoint>): String {
        val root = JsonObject()
        root.addProperty("type", "FeatureCollection")
        val features = JsonArray()
        if (points.isNotEmpty()) {
            // 整体一条 LineString
            val lineFeature = JsonObject()
            lineFeature.addProperty("type", "Feature")
            val geom = JsonObject()
            geom.addProperty("type", "LineString")
            val coords = JsonArray()
            for (p in points) {
                val c = JsonArray()
                c.add(p.longitude)
                c.add(p.latitude)
                c.add(p.altitude)
                coords.add(c)
            }
            geom.add("coordinates", coords)
            lineFeature.add("geometry", geom)
            val props = JsonObject()
            props.addProperty("start", points.first().timestamp)
            props.addProperty("end", points.last().timestamp)
            lineFeature.add("properties", props)
            features.add(lineFeature)

            // 每个点一个 Feature（便于单个点查询）
            for (p in points) {
                val f = JsonObject()
                f.addProperty("type", "Feature")
                val g = JsonObject()
                g.addProperty("type", "Point")
                val c = JsonArray()
                c.add(p.longitude)
                c.add(p.latitude)
                g.add("coordinates", c)
                f.add("geometry", g)
                val props = JsonObject()
                props.addProperty("time", p.timestamp)
                props.addProperty("accuracy", p.accuracy)
                f.add("properties", props)
                features.add(f)
            }
        }
        root.add("features", features)
        return gson.toJson(root)
    }

    fun writeExportFile(context: Context, points: List<TrackPoint>, prefix: String): File {
        val dir = File(context.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        val name = "$prefix-${System.currentTimeMillis()}.geojson"
        val f = File(dir, name)
        f.writeText(exportGeoJson(points))
        return f
    }
}
