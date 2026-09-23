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
        /** OpenStreetMap 瓦片（海外/全球可访问，默认），国内访问可能超时 */
        fun osmTileSource(): XYTileSource = XYTileSource(
            "OpenStreetMap",
            3, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
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
