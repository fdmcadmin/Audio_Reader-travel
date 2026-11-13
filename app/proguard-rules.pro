# Sherpa's JNI reads configuration fields by their original names and constructs
# result objects by their original class/constructor signatures. Keep the whole
# bridge, including default model configurations not directly read by Kotlin.
# Otherwise a debug build works while the compact/release APK aborts in JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
