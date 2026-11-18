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

// Apply the tracked patch only to the exact inspected source profile. Reverse-checking
// makes configuration idempotent; git apply refuses to overwrite conflicting local work.
val miuixSource = file("third_party/miuix")
val miuixPatch = file("third_party/patches/miuix-hyperlpa.patch")
fun miuixGit(vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("git", "-C", miuixSource.absolutePath) + args)
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output.trim()
}
if (miuixSource.resolve(".git").exists()) {
    check(miuixGit("rev-parse", "HEAD").second == "9d4b9c85c18707c7da12cf8c497ea57955fcf81e") {
        "Initialize the pinned Miuix submodule before building: git submodule update --init --recursive"
    }
    if (miuixGit("apply", "--reverse", "--check", miuixPatch.absolutePath).first != 0) {
        val checkPatch = miuixGit("apply", "--check", miuixPatch.absolutePath)
        check(checkPatch.first == 0) { "Miuix patch conflicts with local changes. Preserve those changes and reconcile third_party/patches/miuix-hyperlpa.patch.\n${checkPatch.second}" }
        val applied = miuixGit("apply", miuixPatch.absolutePath)
        check(applied.first == 0) { "Could not apply the pinned Miuix patch: ${applied.second}" }
    }
} else {
    // Fleet snapshots deliberately omit Git metadata. Verify every consumed file
    // against the patched source manifest instead of trusting an unversioned tree.
    val manifest = file("third_party/miuix-source.sha256")
    check(manifest.isFile) { "Missing pinned Miuix source manifest" }
    manifest.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
        val (expected, path) = line.split("  ", limit = 2)
        val source = miuixSource.resolve(path)
        check(source.isFile) { "Missing Miuix source: $path" }
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Miuix source differs from pinned OS4 profile: $path" }
    }
}

// Build every module from that revision and patch to avoid mixing incompatible binaries.
includeBuild("third_party/miuix") {
    dependencySubstitution {
        listOf("core", "ui", "preference", "icons", "glass-icons", "material", "blur", "glass", "squircle", "nav")
            .forEach { name ->
                substitute(module("top.yukonga.miuix.kmp:miuix-$name"))
                    .using(project(":miuix-$name"))
                substitute(module("top.yukonga.miuix.kmp:miuix-$name-android"))
                    .using(project(":miuix-$name"))
            }
    }
}
