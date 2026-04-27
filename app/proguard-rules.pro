# LlamaEngine proguard rules
# 确保 llama.cpp 官方 Android 绑定的 native 方法不被混淆

# 保留 com.arm.aichat 包下的所有类和方法
-keep class com.arm.aichat.** { *; }
-keep interface com.arm.aichat.** { *; }

# 保留 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 JNI 相关
-keep class * implements dalvik.annotation.OptimizationTarget { *; }
-keepclassmembers class * {
    @dalvik.annotation.optimization.FastNative *;
}

# 保留 Kotlin 协程和 Flow
-keepnames class kotlinx.coroutines.flow.** { *; }
-keepclassmembers class kotlinx.coroutines.flow.** { *; }

# 保留 Hilt 注入
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# 保留 Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# 保留 AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# 通用
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
