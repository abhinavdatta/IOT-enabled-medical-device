# ProGuard rules for WaterBench Controller.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-coroutines: we only use dispatchers/jobs; silence unverifiable warnings.
-dontwarn kotlinx.coroutines.**

# --- Gson ---------------------------------------------------------------
# R8 must keep generic signatures or Gson's TypeToken throws
# "TypeToken must be created with a type argument" at runtime.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep the fields Gson reflects over in our persisted models.
-keepclassmembers class com.waterbench.controller.DataRecorder$* { <fields>; }
-keepclassmembers class com.waterbench.controller.DeviceRegistry$* { <fields>; }
-keep class com.waterbench.controller.DataRecorder$* { *; }
-keep class com.waterbench.controller.DeviceRegistry$* { *; }
# --------------------------------------------------------------- Gson ---
