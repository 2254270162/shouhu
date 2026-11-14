import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 高德 Web 服务 Key（可选）。配置后地址解析优先走高德，国内最准；
// 不配置则自动降级到免 Key 的 BigDataCloud。写在 local.properties 里：
//   AMAP_KEY=你的Key
val amapKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    (props.getProperty("AMAP_KEY") ?: System.getenv("AMAP_KEY") ?: "").trim()
}

android {
    namespace = "com.example.locateme"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.locateme"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
        // 预置注册：由服务端构建时注入，默认为空表示不启用自动注册
        buildConfigField("String", "ENROLL_SERVER", "\"\"")
        buildConfigField("String", "ENROLL_GROUP_KEY", "\"\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 读写照片的 EXIF，用于把定位写进照片 / 读出照片原有位置
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // CameraX：App 内实时预览前/后摄像头并拍照，不依赖系统相机应用
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 被监控端与服务端的长连接
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
