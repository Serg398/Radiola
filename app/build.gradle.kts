import groovy.json.JsonSlurper
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
        applicationId = "com.example.sdr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
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
        require(rates.getValue("iqHz").toInt() == 10 * rates.getValue("channelHz").toInt())
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
            .joinToString(",\n") { "    ${it["name"]}(\"${it["label"]}\", ${it["id"]}, ${it["bandwidthHz"]}, ${it["offsetHz"]})" }
        kotlin.resolve("DemodulationMode.kt").writeText("""
            // Generated from src/main/dsp/profiles.json. Edit the descriptor instead.
            package com.example.sdr
            enum class DemodulationMode(val label: String, val nativeId: Int, val bandwidthHz: Int, val passbandOffsetHz: Int) {
            $entries;
            }
        """.trimIndent() + "\n")
        val profiles = modes.joinToString(",\n") { "    {${it["id"]}, ${it["bandwidthHz"]}, ${it["offsetHz"]}, ${it["filterCutoffHz"]}, ${it["inputCutoffHz"]}}" }
        val rateText = """
            #pragma once
            #include <array>
            namespace DspProfiles {
            inline constexpr int iqRate = ${rates["iqHz"]};
            inline constexpr int channelRate = ${rates["channelHz"]};
            inline constexpr int audioRate = ${rates["audioHz"]};
            struct Profile { int id, bandwidthHz, offsetHz, filterCutoffHz, inputCutoffHz; };
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
            array("decimateFive", coefficients(aa.getValue("postTaps").toInt(), aa.getValue("cutoffHz").toDouble() / (rates.getValue("iqHz").toDouble() / 2), false)) + "}\n")
    }
}
androidComponents.onVariants { variant -> variant.sources.kotlin?.addStaticSourceDirectory(dspOutput.get().dir("kotlin").asFile.absolutePath) }
tasks.configureEach {
    if (name.contains("Kotlin") || name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(generateDspProfiles)
}
