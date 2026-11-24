plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gonzalo.atlas"
    compileSdk = 35
    defaultConfig {
        // El paquete de siempre: la versión nueva se instala encima de la de Godot (misma firma de la familia).
        applicationId = "com.gonzalo.atlas"
        minSdk = 29
        targetSdk = 35
        versionCode = 37
        versionName = "2.0.0"
    }
    signingConfigs {
        // La firma la pone la familia (Familia-Revista vuelve a firmar con familia.keystore). Aquí, una clave provisional:
        // la de ATLAS_KS (CI) o la de depuración.
        create("provisional") {
            val ks = System.getenv("ATLAS_KS")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks); storePassword = System.getenv("ATLAS_KS_PASS"); keyAlias = System.getenv("ATLAS_KS_ALIAS") ?: "atlas"; keyPassword = System.getenv("ATLAS_KS_PASS")
            } else {
                val dbg = File(System.getProperty("user.home"), ".android/debug.keystore")
                storeFile = dbg; storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("provisional")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") } }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
                System.getProperty("capturas")?.let { c -> it.systemProperty("capturas", c) }
                it.dependsOn("copyRobolectricDeps")
                it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
            }
        }
    }
}
val robolectricDeps: Configuration by configurations.creating
tasks.register<Copy>("copyRobolectricDeps") { from(robolectricDeps); into(layout.buildDirectory.dir("robolectric-deps")) }

dependencies {
    robolectricDeps("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.20.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.20.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
