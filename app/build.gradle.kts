import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credentials aus keystore.properties im Projekt-Root.
// Diese Datei steht in .gitignore und wird NICHT committet.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.steimerbyte.bulkcompressor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.steimerbyte.bulkcompressor"
        // Android 7.0: laeuft auch auf alten Geraeten. Der Medienpfad
        // braucht nicht mehr als MediaCodec + MediaMuxer (API 18+).
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.7.3"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile") ?: "bulkcompressor.keystore")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            // v1 fuer alte Installer, v2/v3 fuer Android ab 7.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildFeatures {
        // Der Marker schreibt die App-Version in die Metadaten der Datei -
        // dafuer braucht es BuildConfig, das AGP nicht mehr von allein baut.
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Dateiliste: nur sichtbare Zeilen werden gehalten, wichtig auf
    // Geraeten mit wenig RAM.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // EXIF lesen und zurueckschreiben, damit die Chronologie der Galerie
    // erhalten bleibt (Aufnahmedatum, GPS, Geraet).
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
    // Oberflaeche im JVM-Test: prueft Layout-Inflation, Verkabelung und
    // die Logdatei, ohne Geraet oder Emulator.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
