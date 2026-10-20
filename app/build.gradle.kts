import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bump these two for every release: versionCode must always go up.
val appVersionCode = 2
val appVersionName = "1.1.0"

val env: Map<String, String> = System.getenv()
fun envOr(key: String, fallback: String) = env[key]?.takeIf { it.isNotBlank() } ?: fallback
// Signing key: a repo secret if you set one, otherwise the key in signing/ (fine for a private tool).
// Keeping the same key on every build is what lets the Karoo install updates over the old version.
val keystoreB64: String? = env["KEYSTORE_BASE64"]?.takeIf { it.isNotBlank() }
    ?: rootProject.file("signing/voicenav.jks.b64").takeIf { it.exists() }?.readText()
val hasReleaseKey = keystoreB64 != null
// GitHub Actions sets GITHUB_REPOSITORY to "your-name/karoo-voice-nav".
val repo = env["GITHUB_REPOSITORY"] ?: "OWNER/karoo-voice-nav"

android {
    namespace = "com.trektravel.voicenav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trektravel.voicenav"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "manifest_url", "https://github.com/$repo/releases/latest/download/manifest.json")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val ks = File.createTempFile("release", ".jks")
                ks.writeBytes(Base64.getMimeDecoder().decode(keystoreB64!!.trim()))
                storeFile = ks
                storePassword = envOr("KEYSTORE_PASSWORD", "voicenav")
                keyAlias = envOr("KEY_ALIAS", "voicenav")
                keyPassword = envOr("KEY_PASSWORD", "voicenav")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // Plain Switch widgets and getIdentifier() are deliberate; don't fail the build over style.
        abortOnError = false
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    testImplementation("junit:junit:4.13.2")
}

// Writes the small JSON file the Karoo reads to offer "Update" for sideloaded apps.
tasks.register("writeKarooManifest") {
    doLast {
        val json = """
            {
              "label": "Voice Nav",
              "packageName": "com.trektravel.voicenav",
              "latestApkUrl": "https://github.com/$repo/releases/latest/download/voice-nav.apk",
              "latestVersion": "$appVersionName",
              "latestVersionCode": $appVersionCode,
              "developer": "Trek Travel",
              "description": "Spoken turn-by-turn directions while following a route."
            }
        """.trimIndent()
        layout.buildDirectory.file("karoo/manifest.json").get().asFile.apply {
            parentFile.mkdirs(); writeText(json)
        }
    }
}
