pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Meta Wearables Device Access Toolkit (mwdat)
        // Requires GitHub Packages authentication — set credentials in local.properties:
        //   github.username=YOUR_GITHUB_USERNAME
        //   github.token=YOUR_GITHUB_PAT  (needs read:packages scope)
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                val props = java.util.Properties().apply {
                    val localProps = rootProject.file("local.properties")
                    if (localProps.exists()) load(localProps.inputStream())
                }
                username = props.getProperty("github.username") ?: System.getenv("GITHUB_USERNAME")
                password = props.getProperty("github.token") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "RayBanHABridge"
include(":app")
