# JNI exports and FindClass/GetMethodID use these exact JVM names and constructors.
-keep class com.example.sdr.NativeSdr { *; }
-keep class com.example.sdr.NativeFtx { *; }
-keep class com.example.sdr.audio.HamNoiseNative { *; }
-keep class com.example.sdr.SpectrumFrame { *; }
-keep class com.example.sdr.RdsFrame { *; }
# Keep the critical DSP adapter APIs testable while allowing optimization of their bodies.
-keep,allowoptimization class com.example.sdr.decoders.cw.** { *; }
-keep,allowoptimization class com.example.sdr.audio.HamNoiseProcessor { *; }
-keep,allowoptimization class com.example.sdr.audio.HamNoiseSettings$Config { *; }

# ONNX uses JNI; Rhino discovers built-in JS objects through reflection.
# Keep these boundaries conservative while shrinking the Android application code.
-keep class ai.onnxruntime.** { *; }
-keep class !org.mozilla.javascript.tools.**,org.mozilla.javascript.** { *; }
# Desktop shell/debugger tools depend on AWT/Swing and must not enter an Android APK.
-checkdiscard class org.mozilla.javascript.tools.debugger.**
# Rhino's optional Java-bean JSON converter references desktop-only APIs.
# The plugin sandbox exposes no Java objects (initSafeStandardObjects + closed ClassShutter).
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
