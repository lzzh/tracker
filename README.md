# 一生足迹 (LifeTrace)

一个安卓轨迹记录 App，复刻"一生足迹"类应用的核心功能。

## 功能
1. **后台保活**：前台服务 + 持续定位，配合忽略电池优化
2. **轨迹记录**：高精度连续记录经纬度、海拔、速度、方向
3. **历史记录**：按 今天/本周/本月/今年/一生 查看轨迹
4. **地图**：OSMDroid + OpenStreetMap，全球瓦片，支持国内/国外
5. **时间查询**：选定时间段，地图回放该时间的位置轨迹
6. **导入导出**：GeoJSON（可被多数地图工具打开）
7. **WebDAV 自动同步**：把轨迹上传到你的 WebDAV

## 构建
通过 GitHub Actions 云端编译（见 .github/workflows/build-apk.yml），产物为 debug APK。

## 权限
- ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION / ACCESS_BACKGROUND_LOCATION
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION
- POST_NOTIFICATIONS
- INTERNET（WebDAV/地图瓦片）
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（保活）
