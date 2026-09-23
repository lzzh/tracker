package com.coomi.lifetrace

import android.app.Application
import org.osmdroid.config.Configuration

class LifeTraceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OSMDroid 初始化：使用应用私有缓存目录存瓦片，避免外部存储权限问题
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
    }
}
