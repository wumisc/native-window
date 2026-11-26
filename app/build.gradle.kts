plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.wumisc.nativewindow"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.wumisc.nativewindow"
        minSdk = 34
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0-deep-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly(files(rootProject.file("libs/api-82.jar")))
}
