# AIxOrigin App ProGuard 规则

# ---- 高德地图/定位 ----
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-keep class com.aix.origin.app.** { *; }

-keep class com.amap.api.maps.** { *; }
-keep class com.autonavi.amap.mapcore.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# 保留模型(数据类可能被反射/JSON 使用)
-keep class com.aix.origin.app.model.** { *; }
