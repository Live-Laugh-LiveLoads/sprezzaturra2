plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Set ideaforge.whisperNative=false in gradle.properties (or -P) to skip compiling whisper.cpp.
// The Kotlin side then reports WhisperNative.isAvailable() == false instead of crashing.
val whisperNative = providers.gradleProperty("ideaforge.whisperNative").orNull?.toBoolean() ?: true

android {
    namespace = "com.ideaforge.whisper"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        if (whisperNative) {
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    // Inference is unusably slow unoptimised, so even debug builds get -O3.
                    arguments += listOf(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_C_FLAGS=-O3",
                        "-DCMAKE_CXX_FLAGS=-O3",
                        "-DANDROID_STL=c++_static",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    )
                }
            }
        }
    }

    if (whisperNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
