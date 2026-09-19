plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Свой keystore подставляется переменными окружения (в CI — из GitHub Secrets).
val releaseKeystorePath: String? = System.getenv("STRAVO_KEYSTORE_FILE")

android {
    namespace = "com.stravo.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stravo.vpn"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
    }

    signingConfigs {
        if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("STRAVO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRAVO_KEY_ALIAS")
                keyPassword = System.getenv("STRAVO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Тот же ключ, что и у release: иначе debug- и release-APK нельзя
            // поставить друг поверх друга, а данные приложения при этом теряются.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Если владелец не задал собственный keystore — подписываем debug-ключом,
            // чтобы релизный APK устанавливался на устройство.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Ядро туннеля: libbox.aar собирается в CI (workflow libbox.yml) и подтягивается
// в app/libs перед сборкой. В git бинарник не попадает.
val libboxAar = file("libs/libbox-legacy.aar")
if (!libboxAar.exists()) {
    throw GradleException(
        "Не найден app/libs/libbox-legacy.aar — ядро sing-box. " +
            "Он собирается workflow libbox.yml и подтягивается в CI автоматически.",
    )
}

dependencies {
    implementation(files(libboxAar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.zxing.core)

    // Сканер QR на телефоне (на TV не используется).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
}
