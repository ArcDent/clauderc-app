import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing lives outside the repo in .local/ (git-ignored, mode 600). Without it the
// release APK is built unsigned.
val signingFile = rootProject.file("../.local/android-signing.properties")
val signing = signingFile.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "clauderc.app"
    compileSdk = 37
    // Use the build tools already installed on this machine (tasks.md group 11).
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "clauderc.app"
        minSdk = 29
        targetSdk = 37
        // The release workflow passes both from the git tag as Gradle properties.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0.0-beta")
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimizes the code, which makes Compose noticeably smoother than an
            // unoptimized build. Serialization, OkHttp, coroutines and DataStore ship their own rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        // Protocol samples shared with the Go side (tasks 2.3 and 11.3).
        getByName("test").resources.directories.add("../../internal/protocol/testdata")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric touches FileDescriptor internals, closed by default on JDK 21.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                // The theme test compares tokens against the approved mockup.
                it.systemProperty("clauderc.mockup", rootProject.file("../design/ui-mockup/index.html").absolutePath)
                // ./gradlew testDebugUnitTest -Pscreens renders the main pages to build/screens for review.
                if (project.hasProperty("screens")) {
                    it.systemProperty("clauderc.screens", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.android.embedded)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    // Replies are cut into blocks by a CommonMark parser; the chat draws the blocks itself.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    // Formulas in replies: a Compose LaTeX renderer with the KaTeX fonts, no native code (TODOLIST PoC).
    implementation(libs.latex.renderer)
    implementation(libs.latex.fonts.katex)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
