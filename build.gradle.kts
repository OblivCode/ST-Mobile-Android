plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "app.stmobile"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    fun envOrProp(name: String): String? =
        (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }

    val releaseStoreFile = envOrProp("RELEASE_STORE_FILE")
    val releaseStorePassword = envOrProp("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = envOrProp("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = envOrProp("RELEASE_KEY_PASSWORD") ?: releaseStorePassword
    val signingAvailable = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank()

    defaultConfig {
        applicationId = "app.stmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrProp("VERSION_CODE")?.toIntOrNull()
            ?: (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = (envOrProp("VERSION_NAME")
            ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
            ?: "0.1.0")

        ndk {
            // The FongMi runtime bundle is arm64-v8a (+ armeabi-v7a); v1 ships arm64.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-DNODE_SHARED_MODE", "-std=c++20")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (signingAvailable) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Executables-as-native-libs strategy from the feasibility deep-dive:
    // useLegacyPackaging forces .so extraction to nativeLibraryDir at install
    // time (this is what makes later binary-exec approaches work, and it is
    // the same mechanism ST-android uses at targetSdk 36).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Our NDK (r25) and the FongMi runtime (NDK r27d) both provide
            // libc++_shared.so; keep one copy.
            pickFirsts += "**/libc++_shared.so"
            // Do not strip the launcher — it is an executable, not a library.
            keepDebugSymbols += "**/libstnode.so"
        }
    }

    // NOTE: do not add "tar" to noCompress — the payload must be deflated in
    // the APK. (AGP decompresses .gz assets during merge, so the bundle ships
    // as a plain .tar and is compressed by the packaging step instead.)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // YAML two-way sync for SillyTavern config.yaml (Phase B)
    implementation("org.yaml:snakeyaml:2.2")

    // Streaming tar extraction for the bundled SillyTavern payload.
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Standard and AES-256 ZIP archive management (Phase F)
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // Phase 4 UI shell (D7: Kotlin + Jetpack Compose).
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.13.1")

    // JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}