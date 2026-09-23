package com.coomi.lifetrace

import android.app.Application
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource

class LifeTraceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OSMDroid 初始化：使用应用私有缓存目录存瓦片，避免外部存储权限问题
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
    }

    companion object {
        /** OpenStreetMap 法国镜像（实测海外可用；OSM 官方/德国镜像在部分地区超时）。默认。 */
        fun osmFrTileSource(): XYTileSource = XYTileSource(
            "osm_fr",
            3, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"
            ),
            "© OpenStreetMap"
        )

        /** OpenStreetMap 德国镜像（部分地区可用，作为可选） */
        fun osmDeTileSource(): XYTileSource = XYTileSource(
            "osm_de",
            3, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://a.tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.de/{z}/{x}/{y}.png"
            ),
            "© OpenStreetMap"
        )

        /** OpenStreetMap 官方（部分海外/国内地区会超时，作为可选） */
        fun osmTileSource(): XYTileSource = XYTileSource(
            "OpenStreetMap",
            3, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"
            ),
            "© OpenStreetMap"
        )

        /** 高德 road 瓦片（国内可访问，无需 key），海外（如越南）访问不通 */
        fun amapTileSource(): XYTileSource = XYTileSource(
            "amap",
            3, 19, 256, ".png",
            arrayOf(
                "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"
            ),
            "© 高德地图"
        )
    }
}
