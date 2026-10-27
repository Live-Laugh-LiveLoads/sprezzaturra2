# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ideaforge.**$$serializer { *; }
-keepclassmembers class com.ideaforge.** { *** Companion; }
-keepclasseswithmembers class com.ideaforge.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNI entry points of the whisper.cpp bridge.
-keep class com.ideaforge.whisper.WhisperNative { native <methods>; }

# Tink (EncryptedSharedPreferences) references annotations it does not ship.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
