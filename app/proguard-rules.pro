# ProGuard rules for VisionAid AI

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep VisionAid service classes
-keep class com.visionaid.app.service.** { *; }

# OkHttp (Phase 2)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
