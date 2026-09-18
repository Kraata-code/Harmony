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

tasks.register<Zip>("packageNowPlayingCore") {
    from(layout.projectDirectory.dir("now-playing-native-assets")) {
        include("matcher_tah.leveldb", "v3_config_tah.pb")
    }
    archiveFileName.set("harmony-now-playing-core.zip")
    destinationDirectory.set(nowPlayingOutput)
}

tasks.register<Zip>("packageNowPlayingMx") {
    from(layout.projectDirectory.dir("now-playing-databases/mx"))
    archiveFileName.set("harmony-now-playing-mx.zip")
    destinationDirectory.set(nowPlayingOutput)
}

tasks.register<Zip>("packageNowPlayingUsXa") {
    from(layout.projectDirectory.dir("now-playing-databases/us-xa"))
    archiveFileName.set("harmony-now-playing-us-xa.zip")
    destinationDirectory.set(nowPlayingOutput)
}

tasks.register("packageNowPlayingAssets") {
    dependsOn("packageNowPlayingCore", "packageNowPlayingMx", "packageNowPlayingUsXa")
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
