plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gsalem.unitautostart"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gsalem.unitautostart"
        minSdk = 27
        // 27 de proposito: o aparelho e Android 8.1; targetSdk alto bloquearia
        // iniciar atividade em segundo plano (abrir o player e voltar ao HOME).
        targetSdk = 27
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Side-load num aparelho de 2019: o lint vital so barra o build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 1.4.3 ainda traz androidx.media.MediaBrowserCompat com getTransportControls();
    // a 1.7.0 removeu o cliente (fica so android.support.v4, sem controles).
    implementation("androidx.media:media:1.4.3")
    implementation("androidx.core:core:1.9.0")
}
