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
        versionCode = 29
        versionName = "0.23.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The app's languages. Library resources in other languages are left out, so every screen is in the
    // chosen language. A new language also goes into res/xml/locales_config.xml and AppLanguage.
    androidResources {
        localeFilters += listOf("en", "de")
    }
    buildFeatures { compose = true; buildConfig = true }
    // The privacy policy published for Google Play is also shown in the app (Settings).
    sourceSets["main"].assets.srcDir(rootProject.file("docs/privacy"))
    // Signing keys stay out of version control: keystore.properties next to settings.gradle.kts names the
    // keystore files and their passwords. Without an entry, that release is left unsigned.
    //  - storeFile, storePassword, keyAlias, keyPassword: the Google Play upload key (playRelease, AAB).
    //  - githubStoreFile, githubStorePassword, githubKeyAlias, githubKeyPassword: the key of the full version
    //    published on GitHub (fullRelease, APK). Every GitHub release must use it, or updates won't install.
    val keys = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keys?.getProperty("storeFile") != null) create("upload") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
        if (keys?.getProperty("githubStoreFile") != null) create("github") {
            storeFile = rootProject.file(keys.getProperty("githubStoreFile"))
            storePassword = keys.getProperty("githubStorePassword")
            keyAlias = keys.getProperty("githubKeyAlias")
            keyPassword = keys.getProperty("githubKeyPassword")
        }
    }
    // One codebase, two apps. New features go into src/main and reach both; a flavor only
    // decides what Google Play does not allow (see docs/development.md, "Two versions").
    flavorDimensions += "store"
    productFlavors {
        // Sideloaded: keeps the original ID (so installs update in place) and offers all files access.
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
            // Release APKs published on GitHub; "optimized" keeps the development certificate for local use.
            signingConfig = signingConfigs.findByName("github")
        }
        // Google Play: own ID and name (src/play); hidden media only through folders chosen in the picker.
        create("play") {
            dimension = "store"
            applicationId = "com.norfila.gallery"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
            signingConfig = signingConfigs.findByName("upload")
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
            // Signed with the flavor's key (see productFlavors).
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
    // Full: "optimized" (development certificate, local installs) and "release" (GitHub key, published APK).
    // Play: "release" only (upload key, AAB).
    beforeVariants { variant ->
        val store = variant.productFlavors.single { it.first == "store" }.second
        if (store == "play" && variant.buildType == "optimized") variant.enable = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    // Per-app language: remembers the choice and applies it on Android 12 and older too.
    implementation("androidx.appcompat:appcompat:1.7.1")
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
