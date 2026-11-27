plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing: your private upload key via PHASE_KEYSTORE_* (CI reads
// them from repository secrets; locally, from environment variables). Without
// them, release builds fall back to the test key in keystore/ — fine for
// testing on your own phone, never for Google Play.
val ksFile = System.getenv("PHASE_KEYSTORE_FILE")?.let { file(it) } ?: file("../keystore/phase-sideload.jks")
val ksPass = System.getenv("PHASE_KEYSTORE_PASSWORD") ?: "phase-sideload"
val ksAlias = System.getenv("PHASE_KEY_ALIAS") ?: "phase"
val ksKeyPass = System.getenv("PHASE_KEY_PASSWORD") ?: ksPass

val phaseAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val abiSplits = (findProperty("abiSplits") as String?)?.toBoolean() == true

android {
    namespace = "app.phase.stem"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.phase.stem"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("PHASE_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("PHASE_VERSION_NAME") ?: "0.1.0"
        // `-PabiSplits=true` builds one APK per ABI (for direct downloads);
        // otherwise ABIs are filtered here and the AAB splits them for Play.
        if (!abiSplits) ndk { abiFilters += phaseAbis }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math", "-fno-finite-math-only")
                // 16 KB page-size compatible native libs (required by Google Play).
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false // uncompressed, 16 KB aligned
    }

    // The AI model is memory-mapped straight from the APK, so it must not be compressed.
    androidResources { noCompress += "onnx" }

    bundle {
        // Ship the model in the base module; Play serves per-ABI/density splits.
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }
    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*phaseAbis.toTypedArray())
            isUniversalApk = true
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media:media:1.7.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.oboe:oboe:1.9.3")
    implementation(project(":separation"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
