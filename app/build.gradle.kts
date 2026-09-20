import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Read version from version.properties
val versionFile = rootProject.file("version.properties")
val versionProperties = Properties()
if (versionFile.exists()) {
    versionProperties.load(versionFile.inputStream())
}
val baseVersion = versionProperties.getProperty("VERSION_BASE", "0.1.0")

android {
    namespace = "com.trustnetid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trustnetid.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = baseVersion
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Append "-dev" to version for debug builds
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            buildConfigField("String", "APP_VERSION", "\"$baseVersion-dev\"")
            buildConfigField("String", "VERSION_NAME", "\"$baseVersion-dev\"")
        }
        
        release {
            isMinifyEnabled = false
            // Release version has no suffix (production ready)
            buildConfigField("String", "APP_VERSION", "\"$baseVersion\"")
            buildConfigField("String", "VERSION_NAME", "\"$baseVersion\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.10.0")
    
    // Camera
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    
    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.0")
    
    // JMRTD (passport/ID reading with full eMRTD stack)
    implementation("org.jmrtd:jmrtd:0.8.7") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
