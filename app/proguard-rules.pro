# Proguard rules for MemoryFix
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.apadev.memoryfix.model.** { *; }
