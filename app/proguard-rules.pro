# NanoHTTPD 有反射式的内容协商与 MIME 处理，保守起见整体保留。
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-dontwarn org.nanohttpd.**
-keepattributes *Annotation*
