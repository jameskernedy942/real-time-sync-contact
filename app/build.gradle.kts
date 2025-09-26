plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.realtime.synccontact"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.realtime.synccontact"
        minSdk = 24
        targetSdk = 36
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
//    compileOptions {
//        sourceCompatibility = JavaVersion.VERSION_11
//        targetCompatibility = JavaVersion.VERSION_11
//    }
//    kotlinOptions {
//        jvmTarget = "11"
//    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

//    packagingOptions {
//        resources {
//            excludes += setOf(
//                "META-INF/DEPENDENCIES",
//                "META-INF/LICENSE",
//                "META-INF/LICENSE.txt",
//                "META-INF/license.txt",
//                "META-INF/NOTICE",
//                "META-INF/NOTICE.txt",
//                "META-INF/notice.txt",
//                "META-INF/ASL2.0",
//                "META-INF/INDEX.LIST",
//                "META-INF/io.netty.versions.properties"
//            )
//        }
//    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(platform("com.google.firebase:firebase-bom:34.3.0"))
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    implementation("com.google.firebase:firebase-analytics")

    // RabbitMQ AMQP Client
    implementation("com.rabbitmq:amqp-client:5.20.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // WorkManager for background work
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Notification support
    implementation("androidx.core:core-ktx:1.12.0")

    // CardView for UI
    implementation("androidx.cardview:cardview:1.0.0")

    // Permissions handling
    implementation("com.karumi:dexter:6.2.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.room:room-runtime:2.8.0")
    kapt("androidx.room:room-compiler:2.8.0")

//    val room_version = "2.8.0"
//
//    implementation("androidx.room:room-runtime:$room_version")

//    // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
//    // See Add the KSP plugin to your project
//    kapt("androidx.room:room-compiler:$room_version")

//    // If this project only uses Java source, use the Java annotationProcessor
//    // No additional plugins are necessary
//    annotationProcessor("androidx.room:room-compiler:$room_version")
//
//    // optional - Kotlin Extensions and Coroutines support for Room
//    implementation("androidx.room:room-ktx:$room_version")

//    // optional - RxJava2 support for Room
//    implementation("androidx.room:room-rxjava2:$room_version")
//
//    // optional - RxJava3 support for Room
//    implementation("androidx.room:room-rxjava3:$room_version")
//
//    // optional - Guava support for Room, including Optional and ListenableFuture
//    implementation("androidx.room:room-guava:$room_version")

//    // optional - Test helpers
//    testImplementation("androidx.room:room-testing:$room_version")
//
//    // optional - Paging 3 Integration
//    implementation("androidx.room:room-paging:$room_version")
}

kapt {
    correctErrorTypes = true
}