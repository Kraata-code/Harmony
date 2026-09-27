import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.hilt) apply (false)
    alias(libs.plugins.kotlin.ksp) apply (false)
    alias(libs.plugins.aboutlibraries) apply (false)
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
    dependencies {
        classpath(libs.gradle)
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
    }
}

tasks.register<Delete>("Clean") {
    delete(rootProject.layout.buildDirectory)
}

val nowPlayingOutput = layout.buildDirectory.dir("now-playing")

val nowPlayingShardGroups = listOf(
    "ar", "au", "br", "ca", "ch", "de", "es", "fr", "gb",
    "ie", "in", "it", "jp", "mx", "nl", "ru", "us-xa",
)

tasks.register<Zip>("packageNowPlayingCore") {
    from(layout.projectDirectory.dir("now-playing-native-assets")) {
        include("matcher_tah.leveldb", "v3_config_tah.pb")
    }
    archiveFileName.set("harmony-now-playing-core.zip")
    destinationDirectory.set(nowPlayingOutput)
}

nowPlayingShardGroups.forEach { group ->
    val taskSuffix = group.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    tasks.register<Zip>("packageNowPlaying$taskSuffix") {
        from(layout.projectDirectory.dir("now-playing-databases/$group"))
        archiveFileName.set("harmony-now-playing-$group.zip")
        destinationDirectory.set(nowPlayingOutput)
    }
}

tasks.register("packageNowPlayingAssets") {
    dependsOn("packageNowPlayingCore")
    dependsOn(nowPlayingShardGroups.map { group ->
        val taskSuffix = group.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        "packageNowPlaying$taskSuffix"
    })
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics")
                }
            }
        }
    }
}
