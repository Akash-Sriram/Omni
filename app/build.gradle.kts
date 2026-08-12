plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35
    namespace = "com.multiassist.app"
    
    buildFeatures {
        buildConfig = true
    }
    
    defaultConfig {
        applicationId = "com.multiassist.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 250
        versionName = "2.50"
    }

    signingConfigs {
        create("mykey") {
            storeFile = file("../gptassist-release-key.jks")
            storePassword = "gptassist123"
            keyAlias = "gptassist"
            keyPassword = "gptassist123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("mykey")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("mykey")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    lint {
        disable.add("MissingTranslation")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.woheller69:FreeDroidWarn:+")
}
