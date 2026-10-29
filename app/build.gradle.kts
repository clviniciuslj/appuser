plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.pingreal"
    compileSdk = 35

    // Mantém Java e Kotlin no mesmo alvo de JVM para evitar falha no Gradle.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "br.com.pingreal"
        minSdk = 26
        targetSdk = 35
        // Subir os dois a cada mudança: versionCode +1, versionName 1.1 → 1.2 → ...
        versionCode = 3
        versionName = "1.2"
    }

    // Chave fixa: todo APK gerado (inclusive no GitHub) sai com a mesma assinatura,
    // então dá para instalar por cima da versão anterior sem desinstalar.
    signingConfigs {
        create("pingreal") {
            storeFile = file("pingreal.keystore")
            storePassword = "pingreal"
            keyAlias = "pingreal"
            keyPassword = "pingreal"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pingreal")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("pingreal")
        }
    }
}

dependencies {
    // FileProvider, usado para compartilhar a imagem do relatório.
    implementation("androidx.core:core:1.13.1")
}
