# OkHttp（仅用于 WebSocket 实时行情）
# OkHttp 自带 consumer rules，这里只补它在 R8 下会报的可选依赖警告：
# 这些类只在 JVM 桌面端做 TLS provider 时用到，Android 上不会走到。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
