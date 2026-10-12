plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.fluent"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.fluent"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // One shared key, so updates install over the previous version (sideloaded app, no store).
    signingConfigs {
        create("shared") {
            storeFile = file("../fluent.keystore")
            storePassword = "fluent-sideload"
            keyAlias = "fluent"
            keyPassword = "fluent-sideload"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/web"))
}

// The interface is the web page built by ../build.sh (index.html at the repository root).
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.buildDirectory.dir("generated/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
