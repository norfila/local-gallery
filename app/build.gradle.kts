import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.luma.gallery"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.luma.gallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "0.22.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    // The privacy policy published for Google Play is also shown in the app (Settings).
    sourceSets["main"].assets.srcDir(rootProject.file("docs/privacy"))
    // One codebase, two apps. New features go into src/main and reach both; a flavor only
    // decides what Google Play does not allow (see README, "Two versions").
    flavorDimensions += "store"
    productFlavors {
        // Sideloaded: keeps the original ID (so installs update in place) and offers all files access.
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
        // Google Play: own ID and name (src/play); hidden media only through folders chosen in the picker.
        create("play") {
            dimension = "store"
            applicationId = "com.norfila.gallery"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
    }
    // The Play upload key stays out of version control: keystore.properties next to this project
    // names the keystore file and its passwords. Without it, Play builds are left unsigned.
    val uploadKey = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
        create("optimized") {
            initWith(getByName("release"))
            // Same development certificate as earlier APKs: installing keeps albums/ratings.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

androidComponents {
    // Sideloaded builds ship as "optimized" (development certificate), Play builds as "release" (upload key).
    beforeVariants { variant ->
        val store = variant.productFlavors.single { it.first == "store" }.second
        if (store == "play" && variant.buildType == "optimized" || store == "full" && variant.buildType == "release") variant.enable = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose's transitive Espresso is older; 3.7 supports Android 17's input APIs.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
