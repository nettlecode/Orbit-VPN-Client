# gomobile bindings are called from native code via JNI.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep class * implements io.nekohasekai.libbox.** { *; }
-keep class io.nekohasekai.xraybridge.** { *; }
-keep class * implements io.nekohasekai.xraybridge.** { *; }

-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.orbit.vpn.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
