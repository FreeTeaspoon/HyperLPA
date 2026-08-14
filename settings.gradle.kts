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
    }
}

rootProject.name = "HyperLPA"
include(":app")
include(":libs:lpac-jni")

// Progressive blur is provided by the Miuix revision used by the demo app.
includeBuild("third_party/miuix") {
    dependencySubstitution {
        substitute(module("top.yukonga.miuix.kmp:miuix-blur-android"))
            .using(project(":miuix-blur"))
        substitute(module("top.yukonga.miuix.kmp:miuix-ui"))
            .using(project(":miuix-ui"))
        substitute(module("top.yukonga.miuix.kmp:miuix-nav"))
            .using(project(":miuix-nav"))
    }
}
