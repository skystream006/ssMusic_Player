import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

val serverEnvironment = Properties().apply {
    val environmentFile = rootProject.file("../.env")
    if (environmentFile.isFile) environmentFile.inputStream().use { load(it) }
}

fun primarySetting(name: String): String? = (
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
        ?: serverEnvironment.getProperty(name)
    )?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotBlank() }

val primaryRpId = requireNotNull(primarySetting("PASSKEY_RP_ID")) {
    "Set primary PASSKEY_RP_ID in ../.env, the environment, or -PPASSKEY_RP_ID."
}.lowercase()
val primaryUri = URI(primarySetting("PASSKEY_ORIGIN") ?: "https://$primaryRpId")
require(primaryUri.scheme == "https" && primaryUri.host.equals(primaryRpId, ignoreCase = true)
    && primaryUri.rawUserInfo == null && primaryUri.rawQuery == null && primaryUri.rawFragment == null
    && primaryUri.rawPath.orEmpty() in listOf("", "/") && (primaryUri.port == -1 || primaryUri.port in 1..65535)) {
    "Primary PASSKEY_ORIGIN must be an HTTPS origin whose hostname matches PASSKEY_RP_ID."
}
val primaryOrigin = URI("https", null, primaryRpId, if (primaryUri.port == 443) -1 else primaryUri.port,
    null, null, null).toASCIIString()

fun gitOutput(vararg arguments: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

require(gitOutput("rev-parse", "--is-shallow-repository") == "false") {
    "APK versioning requires full Git history. Run git fetch --unshallow."
}
val revision = gitOutput("rev-list", "--first-parent", "--count", "HEAD").toInt()
val signingStore = providers.environmentVariable("APK_SIGNING_STORE_FILE").orNull

android {
    namespace = "com.ssytdlp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ssytdlp.app"
        minSdk = 26
        targetSdk = 36
        versionCode = revision + 1
        versionName = "1.0.$revision"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PASSKEY_RP_ID", "\"$primaryRpId\"")
        buildConfigField("String", "PASSKEY_ORIGIN", "\"$primaryOrigin\"")
    }

    signingConfigs {
        if (signingStore != null) create("distribution") {
            storeFile = file(signingStore)
            storePassword = providers.environmentVariable("APK_SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("APK_SIGNING_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("APK_SIGNING_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        if (signingStore != null) {
            getByName("debug").signingConfig = signingConfigs.getByName("distribution")
            getByName("release").signingConfig = signingConfigs.getByName("distribution")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
