plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.rocketglasses.restep.phone"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.rocketglasses.restep.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-ja"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
