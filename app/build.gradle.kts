import groovy.json.JsonSlurper
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? =
    providers.environmentVariable("RADIOLA_${name}").orNull
        ?: signingProperties.getProperty(name)
val signingNames = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
val releaseSigningAvailable = signingNames.all { !signingValue(it).isNullOrBlank() }
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(releaseSigningAvailable) {
            "Release signing is required. Configure keystore.properties or RADIOLA_* environment variables; see docs/RELEASING.md."
        }
        check(rootProject.file(signingValue("STORE_FILE")!!).isFile) {
            "Release keystore file is missing. Restore the existing release key; see docs/RELEASING.md."
        }
    }
}

android {
    namespace = "com.example.sdr"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        applicationId = "com.serg398.radiola"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt().also { require(it > 0) }
        versionName = releaseVersion.getProperty("versionName").also {
            require(it.matches(Regex("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?")))
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        if (releaseSigningAvailable) create("release") {
            storeFile = rootProject.file(signingValue("STORE_FILE")!!)
            storePassword = signingValue("STORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Радиола Dev")
        }
        getByName("release") {
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// One descriptor generates the UI and DSP parameters, including anti-alias coefficients.
val dspOutput = layout.buildDirectory.dir("generated/dsp")
val generateDspProfiles = tasks.register("generateDspProfiles") {
    val source = file("src/main/dsp/profiles.json")
    inputs.file(source)
    outputs.dir(dspOutput)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val config = JsonSlurper().parse(source) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val rates = config["rates"] as Map<String, Number>
        @Suppress("UNCHECKED_CAST")
        val aa = config["antiAlias"] as Map<String, Number>
        @Suppress("UNCHECKED_CAST")
        val modes = (config["modes"] as List<Map<String, Any>>).sortedBy { (it["id"] as Number).toInt() }
        require(rates.getValue("iqHz").toInt() == 12 * rates.getValue("channelHz").toInt())
        require(rates.getValue("channelHz").toInt() == 5 * rates.getValue("audioHz").toInt())
        require(aa.getValue("halfBandTaps").toInt() % 4 == 3)
        require(modes.map { (it["id"] as Number).toInt() } == modes.indices.toList())
        modes.forEach { mode ->
            require((mode["bandwidthHz"] as Number).toInt() == 2 * (mode["filterCutoffHz"] as Number).toInt())
        }
        require((modes[0]["bandwidthHz"] as Number).toInt() == rates.getValue("channelHz").toInt())
        fun coefficients(count: Int, cutoff: Double, halfBand: Boolean): List<Double> {
            require(count % 2 == 1)
            val values = MutableList(count) { 0.0 }
            for (i in 0..count / 2) {
                val position = i - count / 2
                val sinc = when {
                    position == 0 -> 2 * cutoff
                    halfBand && position % 2 == 0 -> 0.0
                    else -> Math.sin(2 * Math.PI * cutoff * position) / (Math.PI * position)
                }
                val window = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (count - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (count - 1))
                values[i] = sinc * window
                values[count - 1 - i] = values[i]
            }
            val sum = values.sum()
            return values.map { it / sum }
        }
        val output = dspOutput.get().asFile
        val cpp = output.resolve("cpp").apply { mkdirs() }
        val kotlin = output.resolve("kotlin/com/example/sdr").apply { mkdirs() }
        val enumOrder = listOf("WFM", "NFM", "AM", "USB", "LSB", "CW")
        val entries = enumOrder.map { name -> modes.first { it["name"] == name } }
            .joinToString(",\n") { "    ${it["name"]}(\"${it["label"]}\", ${it["id"]}, ${it["bandwidthHz"]}, ${it["offsetHz"]}, ${it["minBandwidthHz"]}, ${it["maxBandwidthHz"]}, listOf(${(it["bandwidthPresetsHz"] as List<*>).joinToString(", ")}))" }
        kotlin.resolve("DemodulationMode.kt").writeText("""
            // Generated from src/main/dsp/profiles.json. Edit the descriptor instead.
            package com.example.sdr
            enum class DemodulationMode(val label: String, val nativeId: Int, val bandwidthHz: Int, val passbandOffsetHz: Int, val minBandwidthHz: Int, val maxBandwidthHz: Int, val bandwidthPresetsHz: List<Int>) {
            $entries;
            }
        """.trimIndent() + "\n")
        val profiles = modes.joinToString(",\n") { "    {${it["id"]}, ${it["bandwidthHz"]}, ${it["offsetHz"]}, ${it["filterCutoffHz"]}, ${it["inputCutoffHz"]}, ${it["minBandwidthHz"]}, ${it["maxBandwidthHz"]}}" }
        val rateText = """
            #pragma once
            #include <array>
            namespace DspProfiles {
            inline constexpr int iqRate = ${rates["iqHz"]};
            inline constexpr int channelRate = ${rates["channelHz"]};
            inline constexpr int audioRate = ${rates["audioHz"]};
            struct Profile { int id, bandwidthHz, offsetHz, filterCutoffHz, inputCutoffHz, minBandwidthHz, maxBandwidthHz; };
            inline constexpr std::array<Profile, ${modes.size}> modes = {{
            $profiles
            }};
            inline constexpr int passbandHz = ${aa["passbandHz"]};
            inline constexpr int stopbandHz = ${aa["stopbandHz"]};
            inline constexpr float minimumStopbandDb = ${aa["minimumStopbandDb"]}.0f;
            inline constexpr float maximumRippleDb = ${aa["maximumRippleDb"]}f;
        """.trimIndent()
        fun array(name: String, values: List<Double>) = "inline constexpr std::array<float, ${values.size}> $name = {{\n" +
            values.chunked(4).joinToString(",\n") { row -> "    " + row.joinToString(", ") { String.format(Locale.ROOT, "%.10ef", it) } } + "\n}};\n"
        cpp.resolve("dsp_profiles.h").writeText(rateText + "\n" +
            array("halfBand", coefficients(aa.getValue("halfBandTaps").toInt(), 0.25, true)) +
            array("postDecimationFilter", coefficients(aa.getValue("postTaps").toInt(), aa.getValue("cutoffHz").toDouble() / (rates.getValue("iqHz").toDouble() / 2), false)) + "}\n")
    }
}
androidComponents.onVariants { variant -> variant.sources.kotlin?.addStaticSourceDirectory(dspOutput.get().dir("kotlin").asFile.absolutePath) }
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease" ||
        name == "packageRelease" || name == "signReleaseBundle") dependsOn(verifyReleaseSigning)
    if (name.contains("Kotlin") || name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(generateDspProfiles)
}
