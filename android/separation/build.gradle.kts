plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure-JVM module so the separation pipeline is unit-tested on the desktop
// ONNX Runtime against the real model. The app supplies the Android runtime
// (same API, same version) — hence compileOnly here.
val ortVersion = "1.32.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "3g"
    // Opt-in end-to-end test against the real model:
    //   PHASE_DEMUCS_ONNX=/path/htdemucs.onnx PHASE_DEMUCS_VECTORS=/path/vec ./gradlew :separation:test
    listOf("PHASE_DEMUCS_ONNX", "PHASE_DEMUCS_VECTORS").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    testLogging { showStandardStreams = true; events("passed", "failed", "skipped") }
}
