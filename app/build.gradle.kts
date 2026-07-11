plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val formalRelease = when (
    val value = providers.environmentVariable("COLOROS_NOTIFY_FORMAL_RELEASE").orNull
        ?.trim()
        ?.lowercase()
) {
    null, "", "false" -> false
    "true" -> true
    else -> error("COLOROS_NOTIFY_FORMAL_RELEASE must be true or false, but was '$value'")
}

android {
    namespace = gropify.project.app.packageName
    compileSdk = gropify.project.android.compileSdk

    signingConfigs {
        val snapshot by creating {
            keyAlias = gropify.project.app.signing.keyAlias
            keyPassword = gropify.project.app.signing.keyPassword
            storeFile = rootProject.file(gropify.project.app.signing.storeFilePath)
            storePassword = gropify.project.app.signing.storePassword
        }
    }
    defaultConfig {
        applicationId = gropify.project.app.packageName
        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk
        versionName = gropify.project.app.versionName
        versionCode = gropify.project.app.versionCode
    }
    buildTypes {
        release {
            // Formal builds stay unsigned until the isolated workflow signing step.
            // Gradle and its plugins never receive the production key or passwords.
            signingConfig = signingConfigs.getByName("snapshot").takeUnless { formalRelease }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.miuix.ui.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.preference.android)
    testImplementation(libs.junit)
}
