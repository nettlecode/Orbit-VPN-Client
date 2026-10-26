import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing: `keystore.properties` in the project root (local builds, git-ignored), or
 * ORBIT_KEYSTORE_FILE / ORBIT_KEYSTORE_PASSWORD / ORBIT_KEY_ALIAS / ORBIT_KEY_PASSWORD (CI).
 * Without either, release APKs are left unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val props = Properties().apply { file.inputStream().use(::load) }
        return@run props.stringPropertyNames().associateWith { props.getProperty(it) }
    }
    val env = System.getenv()
    env["ORBIT_KEYSTORE_FILE"]?.let { store ->
        mapOf(
            "storeFile" to store,
            "storePassword" to env.getValue("ORBIT_KEYSTORE_PASSWORD"),
            "keyAlias" to env.getValue("ORBIT_KEY_ALIAS"),
            "keyPassword" to env.getValue("ORBIT_KEY_PASSWORD"),
        )
    }
}

/** `-PorbitVersion=1.2.3` (CI passes the tag); versionCode is derived as 1_02_03. */
val orbitVersion = (findProperty("orbitVersion") as String?) ?: "0.1.0"
val orbitVersionCode = orbitVersion.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    .coerceAtLeast(1)

android {
    namespace = "app.orbit.vpn"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "app.orbit.vpn"
        minSdk = 24
        targetSdk = 36
        versionCode = orbitVersionCode
        versionName = orbitVersion
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = file(props.getValue("storeFile"))
                storePassword = props.getValue("storePassword")
                keyAlias = props.getValue("keyAlias")
                keyPassword = props.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
}
