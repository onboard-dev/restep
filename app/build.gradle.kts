plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.rocketglasses.soberyobratno"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = if (providers.gradleProperty("isolatedInputTest").isPresent)
            "com.rocketglasses.soberyobratno.inputtest" else "com.rocketglasses.soberyobratno"
        minSdk = 28
        targetSdk = 33
        versionCode = 19
        versionName = "0.19-ja"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // StepMemo: グラスとスマホで共通のファイル形式の部品（ルートの shared/）
    sourceSets {
        getByName("main") { kotlin.srcDir("../shared/src/main/kotlin") }
        getByName("test") { kotlin.srcDir("../shared/src/test/kotlin") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
