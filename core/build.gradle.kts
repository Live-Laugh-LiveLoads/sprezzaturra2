// Pure Kotlin module: everything here runs on a plain JVM, so the agent pipeline, JSON validation
// and transcript stitching are unit-tested without an emulator.
plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    // Fakes shared by the JVM tests here and the app's instrumented test.
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Tests validate against the real agent schemas and prompts shipped in the app's assets.
tasks.test {
    systemProperty("ideaforge.assets", projectDir.resolve("../app/src/main/assets").canonicalPath)
}
