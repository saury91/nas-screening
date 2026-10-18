# WebView 的 JS 桥靠反射调用，别被裁掉
-keepclassmembers class com.projectionroom.tv.NativeBridge {
    public *;
}
