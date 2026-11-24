plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marck.analiseacoes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marck.analiseacoes"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    // Assinatura fixa: as próximas versões instalam por cima
    signingConfigs {
        create("fixa") {
            storeFile = file("acoes.keystore")
            storePassword = "acoes12345"
            keyAlias = "acoes"
            keyPassword = "acoes12345"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
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
