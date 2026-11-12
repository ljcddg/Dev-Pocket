import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 读取项目根目录的 key.properties（签名凭证，不入库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.itcast.ds_balance_viewer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.itcast.ds_balance_viewer"
        minSdk = 24
        targetSdk = 37
        versionCode = 8
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 曾开启 optimization { enable = true }，但 R8 会把 Kotlin 标准库里的
            // 集合类降级成包内可见（package-private），导致继承了它的类在启动时抛
            // IllegalAccessError（崩溃点是 androidx.startup.InitializationProvider）。
            // 这是个人自用工具，稳定性优先，这里关闭 R8 优化/混淆。
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 关闭 Release 构建前的 lint 严格检查（个人工具类 App 无需，且 lint 库不在本地缓存）
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 开启 ViewBinding：用类型安全的绑定对象替代 findViewById
    // 开启 BuildConfig：设置页需要读取 BuildConfig.VERSION_NAME
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
