plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.simpledictionary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.simpledictionary"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // --- AndroidX cơ bản ---
    implementation(libs.androidx.core.ktx.v1131)
    implementation(libs.androidx.appcompat.v171)
    implementation(libs.material.v1130)
    implementation(libs.androidx.constraintlayout)

    // --- Navigation (chỉ giữ 1 version) ---
    implementation(libs.androidx.navigation.fragment.ktx.v295)
    implementation(libs.androidx.navigation.ui.ktx.v295)

    // --- ML Kit mới (KHÔNG dùng play-services-mlkit-*) ---
    implementation(libs.mlkit.text.recognition)
    implementation(libs.language.id)
    implementation(libs.translate.v1702)

    // --- Test ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.v130)
    androidTestImplementation(libs.androidx.espresso.core.v370)

    // ML Kit dịch & nhận dạng ngôn ngữ
    implementation(libs.language.id.v1704)
    implementation(libs.translate)
    implementation(files("libs/vosk-android-0.3.38.aar"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(libs.jna)
}
