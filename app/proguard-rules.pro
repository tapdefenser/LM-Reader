# The reader decoder constructs ImageDecoder and nested result/exception
# objects from libimagedecoder2.so. Its AAR does not supply these JNI rules.
-keep class ca.mpreg.imagedecoder.** { *; }
