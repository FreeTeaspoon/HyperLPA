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

// Miuix 0.9.4 plus pager and navigation fixes, pinned by the submodule commit.
// Build every module from that revision to avoid mixing incompatible binaries.
includeBuild("third_party/miuix") {
    dependencySubstitution {
        listOf("core", "ui", "preference", "icons", "shader", "blur", "squircle", "nav")
            .forEach { name ->
                substitute(module("top.yukonga.miuix.kmp:miuix-$name"))
                    .using(project(":miuix-$name"))
                substitute(module("top.yukonga.miuix.kmp:miuix-$name-android"))
                    .using(project(":miuix-$name"))
            }
    }
}
