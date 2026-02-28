@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

// Configuración de keystore con manejo seguro
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

android {
    namespace = "com.kraata.harmony"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kraata.harmony"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
               arguments(
            "-DGGML_USE_CPU=ON",
            "-DLLAMA_BUILD_EXAMPLES=OFF", // Desactiva ejemplos
            "-DLLAMA_BUILD_TESTS=OFF",     // Desactiva tests
            "-DLLAMA_BUILD_SERVER=OFF",    // Desactiva el servidor HTTP
            "-DLLAMA_CURL=OFF",            // Ya lo teníamos
            "-DLLAMA_BUILD_COMMON=ON",     // Solo lo necesario para la lib
            "-DLLAMA_BUILD_CLI=OFF",       // <--- ESTO ES CLAVE: Desactiva llama-cli
            "-DLLAMA_BUILD_MTMD=OFF"       // <--- ESTO ELIMINA EL ERROR DE 'mtmd.h'
        )
            }
        }
    }

    // Configuración de firma con validación mejorada
    signingConfigs {
        create("ot_release") {
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                val storeFilePath = keystoreProperties["storeFile"] as? String
                val storePass = keystoreProperties["storePassword"] as? String
                val alias = keystoreProperties["keyAlias"] as? String
                val keyPass = keystoreProperties["keyPassword"] as? String

                if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
                    storeFile = rootProject.file(storeFilePath)
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = keyPass
                } else {
                    throw GradleException("Faltan propiedades requeridas en keystore.properties")
                }
            } else {
                logger.warn("keystore.properties no encontrado. La firma de release no estará disponible.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Solo aplicar signingConfig si existe el archivo keystore
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("ot_release")
            }
        }

        debug {
            applicationIdSuffix = ".debug"
        }

        // Build variant userdebug: similar a release pero sin minificación
        create("userdebug") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            matchingFallbacks.addAll(listOf("release"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Configuración de splits por ABI
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // Dimensiones de flavor
    flavorDimensions.add("abi")

    productFlavors {
        create("core") {
            isDefault = true
            dimension = "abi"
        }

        create("full") {
            dimension = "abi"
        }
    }

    // Personalización de nombres de output APK
    applicationVariants.all {
        outputs
            .filterIsInstance<com.android.build.gradle.internal.api.BaseVariantOutputImpl>()
            .forEach { output ->
                val outputFileName = "Harmony-${versionName}-${output.baseName}-${versionCode}.apk"
                output.outputFileName = outputFileName
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    // Exclusión condicional de archivos según build variant
    tasks.withType<KotlinCompile>().configureEach {
        val taskName = name.substringAfter("compile").lowercase()

        if (!taskName.startsWith("full")) {
            exclude("**/*FFmpegScanner.kt")
            exclude("**/*NextRendersFactory.kt")
        } else {
            exclude("**/*FFmpegScannerDud.kt")
            exclude("**/*ffdecoderDud.kt")
        }
    }

    // Configuración de aboutLibraries
    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            // Excluir timestamp para builds reproducibles
            excludeFields = listOf("generated")
        }

        license {
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            allowedLicenses.addAll(
                "Apache-2.0",
                "BSD-3-Clause",
                "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1",
                "GPL-3.0-only",
                "EPL-2.0",
                "MIT",
                "MPL-2.0",
                "Public Domain"
            )
            additionalLicenses.addAll("apache_2_0", "gpl_2_1")
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    // Configuración para builds reproducibles
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    androidResources {
        generateLocaleConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Configuración de KSP
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Utilidades de concurrencia
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)
    implementation(libs.jsoup)
// Parser HTML[citation:4]

    // Para realizar peticiones HTTP
    implementation(libs.okhttp)

    // Para manejo de JSON (si no lo tienes ya)
    implementation(libs.json)

    // Coroutines para operaciones asíncronas
    implementation(libs.kotlinx.coroutines.android)

    // Android Core
    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // Machine Learning
    implementation(libs.onnxruntime.android)

    // Compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // UI
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // Material Design
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)

    // ViewModel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    // Media3
    implementation(libs.media3)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.workmanager)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.runtime)
    implementation(libs.foundation.layout)
    implementation(libs.ui)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Apache Commons
    implementation(libs.apache.lang3)

    // Dependency Injection
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Desugaring para compatibilidad con APIs modernas
    coreLibraryDesugaring(libs.desugaring)

    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // Módulos del proyecto
    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":material-color-utilities"))
    implementation(project(":taglib"))

    // UI adicionales
    implementation(libs.constraintlayout)
    implementation(libs.foundation)

    // Bibliotecas de información
    implementation(libs.aboutlibraries.compose.m3)

    // Soporte para Android N (SDK 24)
    // WebKit 1.14.0 es la última versión compatible con minSdk 24
    implementation("androidx.webkit:webkit:1.14.0")
}

// Dependencias específicas del flavor "full"
afterEvaluate {
    dependencies {
        add("fullImplementation", project(":ffMetadataEx"))
    }
}
