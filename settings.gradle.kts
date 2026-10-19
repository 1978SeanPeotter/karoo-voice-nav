pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// karoo-ext lives on GitHub Packages, which always requires a login, even though it's public.
// In GitHub Actions the built-in token is used automatically (see .github/workflows/build.yml).
// On a PC, put gpr.user / gpr.key (a GitHub token with read:packages) in local.properties.
fun localProperty(key: String): String? {
    val f = file("local.properties")
    if (!f.isFile) return null
    val p = java.util.Properties()
    f.inputStream().use { p.load(it) }
    return p.getProperty(key)
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = System.getenv("GPR_USER") ?: localProperty("gpr.user")
                password = System.getenv("GPR_KEY") ?: localProperty("gpr.key")
            }
        }
    }
}

rootProject.name = "karoo-voice-nav"
include(":app")
