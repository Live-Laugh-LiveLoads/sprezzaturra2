# JNI looks these up by name; R8 must neither rename nor remove them.
-keep class com.ideaforge.whisper.WhisperNative {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
