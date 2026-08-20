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
        exclusiveContent {
            forRepository {
                maven {
                    name = "SignalBuildArtifacts"
                    url = uri("https://build-artifacts.signal.org/libraries/maven/")
                }
            }
            filter {
                includeGroup("org.signal")
            }
        }
    }
}

rootProject.name = "SynapseLocalLlmApp"
include(":app")
include(":privatechat")
