plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.eltermino.android"
    useLibrary("android.test.mock")
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.eltermino"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    // Biometric brings 1.2.5, too old for ActivityResult contracts in a FragmentActivity.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 0 spike: does MINA SSHD run on ART? Needs the testenv servers on the host.
    androidTestImplementation(libs.sshd.core)
    androidTestImplementation(libs.sshd.sftp)
    androidTestImplementation(libs.bouncycastle.prov)
    androidTestImplementation(libs.bouncycastle.pkix)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
