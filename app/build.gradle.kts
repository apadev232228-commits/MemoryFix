import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localFile.inputStream().use { stream ->
        localProperties.load(stream)
    }
}

val admobAppId: String = localProperties.getProperty("admob.app.id") ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerId: String = localProperties.getProperty("admob.banner.id") ?: "ca-app-pub-3940256099942544/6300978111"
val admobInterstitialId: String = localProperties.getProperty("admob.interstitial.id") ?: "ca-app-pub-3940256099942544/1033173712"
val admobRewardedId: String = localProperties.getProperty("admob.rewarded.id") ?: "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "com.apadev.memoryfix"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.apadev.memoryfix"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "PROD_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "PROD_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "PROD_REWARDED_ID", "\"$admobRewardedId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        val keystorePath = localProperties.getProperty("release.keystore.file")
        val storePass = localProperties.getProperty("release.keystore.password")
        val keyAliasStr = localProperties.getProperty("release.key.alias")
        val keyPass = localProperties.getProperty("release.key.password")

        if (!keystorePath.isNullOrEmpty() && !storePass.isNullOrEmpty() && !keyAliasStr.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
            val keyFile = file(keystorePath)
            if (keyFile.exists()) {
                create("release") {
                    storeFile = keyFile
                    storePassword = storePass
                    keyAlias = keyAliasStr
                    keyPassword = keyPass
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.gson)
    implementation(libs.google.ads)
    implementation(libs.billing.ktx)
    implementation(libs.play.review)
    implementation(libs.play.update)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
