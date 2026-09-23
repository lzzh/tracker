package com.coomi.lifetrace.data

/** 一个「常去地点」：进入该范围时暂停记录以省电（可配置多个） */
data class PausePlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Double
)
