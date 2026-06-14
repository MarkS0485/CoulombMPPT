# Add project-specific ProGuard rules here.
# Compose handles itself; kotlinx-serialization needs reflection on data classes.
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.** { *; }
-keepclassmembers class app.coulombmppt.data.model.** { *; }
