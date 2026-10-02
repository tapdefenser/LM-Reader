-keep class com.lmreader.core.vision.NnapiAvailability { *; }

# ORT's JNI constructs metadata/tensor objects by name, including NodeInfo's
# constructor. R8 cannot see those calls; stripping them aborts the process.
# Required by https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# LiteRT's compiled-model JNI also resolves Kotlin classes/constructors by name.
# In particular, a GPU error must create LiteRtException so SEG can fall back
# to CPU instead of aborting while trying to report the original error.
-keep class com.google.ai.edge.litert.** { *; }
