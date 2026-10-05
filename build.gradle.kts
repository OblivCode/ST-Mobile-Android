import java.util.Properties

plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "app.stmobile"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.isFile && keystorePropsFile.canRead()) {
            keystorePropsFile.inputStream().use { stream -> load(stream) }
        }
    }

    fun envOrProp(vararg names: String): String? {
        for (name in names) {
            val prop = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            if (prop != null) return prop
            val env = System.getenv(name)?.takeIf { it.isNotBlank() }
            if (env != null) return env
            val fileProp = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
            if (fileProp != null) return fileProp
        }
        return null
    }

    val releaseStorePath = envOrProp("RELEASE_STORE_FILE", "storeFile")
    val releaseStorePassword = envOrProp("RELEASE_STORE_PASSWORD", "storePassword")
    val releaseKeyAlias = envOrProp("RELEASE_KEY_ALIAS", "keyAlias")
    val releaseKeyPassword = envOrProp("RELEASE_KEY_PASSWORD", "keyPassword") ?: releaseStorePassword

    val releaseStoreFile = releaseStorePath?.let { path ->
        val candidate = file(path)
        if (candidate.isAbsolute) candidate else rootProject.file(path)
    }

    val signingAvailable = releaseStoreFile != null &&
        releaseStoreFile.isFile &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank()

    defaultConfig {
        applicationId = "app.stmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrProp("VERSION_CODE")?.toIntOrNull()
            ?: (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        // Versioning Stance (Phase G): Omit versionName for now and rely strictly
        // on versionCode until an official public release milestone is reached.
        envOrProp("VERSION_NAME")?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
                storeFile = releaseStoreFile
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
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES"
            )
        }
    }

    testOptions {
        animationsDisabled = true
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

    // Tier 3: Connected device instrumentation tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}