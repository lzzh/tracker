package com.coomi.lifetrace.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 常去地点存储：以 JSON 列表形式存 SharedPreferences，支持多个 */
class PlaceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun labels(): Array<String> = prefs.getString("pause_place_names", "")?.split("|")
        ?.filter { it.isNotBlank() }?.toTypedArray() ?: emptyArray()

    fun all(): List<PausePlace> {
        val json = prefs.getString("pause_places", "") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PausePlace>>() {}.type
            gson.fromJson<List<PausePlace>>(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun save(places: List<PausePlace>) {
        prefs.edit()
            .putString("pause_places", gson.toJson(places))
            .putString("pause_place_names", places.joinToString("|") { it.name })
            .apply()
    }

    /** 记录当前定位为常去地点（名称可空，自动用坐标命名） */
    fun addAt(lat: Double, lon: Double, radius: Double, name: String = ""): PausePlace {
        val places = all().toMutableList()
        val p = PausePlace(
            name = name.ifBlank { "地点${places.size + 1}" },
            lat = lat, lon = lon, radius = radius
        )
        places.add(p)
        save(places)
        return p
    }
}
