# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.dondeestamibondi.data.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.dondeestamibondi.data.**$$serializer { *; }
-keepclassmembers class com.dondeestamibondi.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
