/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.generatebp

import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import groovy.util.Node
import groovy.util.NodeList
import groovy.xml.XmlParser
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.kotlin.dsl.get
import org.lineageos.generatebp.ext.aospModulePath
import org.lineageos.generatebp.ext.gradleModuleName
import java.io.File

class GenerateBp(
    private val project: Project,
    private val android: BaseAppModuleExtension,
    private val defaultMinSdkVersion: Int = DEFAULT_MIN_SDK_VERSION,
    private val isAvailableInAOSP: (group: String, artifactId: String) -> Boolean,
) {
    private val configuration = project.configurations["releaseRuntimeClasspath"]

    private val libsBase = File("${project.projectDir.absolutePath}/libs")

    operator fun invoke() {
        // Delete old libs artifacts
        libsBase.deleteRecursively()

        // Update app/Android.bp
        File("${project.projectDir.absolutePath}/Android.bp").let { file ->
            // Read dependencies
            val dependencies = "${spaces(8)}$SHARED_LIBS_HEADER".plus(
                configuration.allDependencies.filter {
                    // kotlin-bom does not need to be added to dependencies
                    it.group != "org.jetbrains.kotlin" && it.name != "kotlin-bom"
                }.joinToString("\n") {
                    if (isAvailableInAOSP(it.group!!, it.name)) {
                        "${spaces(8)}\"${moduleNameAOSP("${it.group}:${it.name}")}\","
                    } else {
                        "${spaces(8)}\"${"${it.group}:${it.name}".aospModuleName}\","
                    }
                }
            )

            // Replace existing dependencies with newly generated ones
            file.writeText(
                file.readText().replace(
                    "static_libs: \\[.*?]".toRegex(RegexOption.DOT_MATCHES_ALL),
                    "static_libs: [%s]".format("\n$dependencies\n${spaces(4)}")
                )
            )
        }

        // Update app/libs
        configuration.resolvedConfiguration.resolvedArtifacts.sortedBy {
            it.moduleVersion.id.gradleModuleName
        }.distinctBy {
            it.moduleVersion.id.gradleModuleName
        }.forEach {
            val id = it.moduleVersion.id

            // Skip modules that are available in AOSP
            if (isAvailableInAOSP(id.group, it.name)) {
                return@forEach
            }

            // Get file path
            val dirPath = "${libsBase}/${id.aospModulePath}"
            val filePath = "${dirPath}/${it.file.name}"

            // Copy artifact to app/libs
            it.file.copyTo(File(filePath))

            // Parse dependencies
            val dependencies = it.file.parentFile.parentFile.walk().filter { file ->
                file.extension == "pom"
            }.map { file ->
                val ret = mutableListOf<String>()

                val pom = XmlParser().parse(file)
                val dependencies = (pom["dependencies"] as NodeList).firstOrNull() as Node?

                dependencies?.children()?.forEach { node ->
                    val dependency = node as Node
                    ret.add(
                        "${
                            (dependency.get("groupId") as NodeList).text()
                        }:${
                            (dependency.get("artifactId") as NodeList).text()
                        }"
                    )
                }

                ret
            }.flatten()

            var targetSdkVersion = android.defaultConfig.targetSdk
            var minSdkVersion = defaultMinSdkVersion

            // Extract AndroidManifest.xml for AARs
            if (it.file.extension == "aar") {
                project.copy {
                    from(project.zipTree(filePath).matching {
                        include("/AndroidManifest.xml")
                    }.singleFile)
                    into(dirPath)
                }

                val androidManifest = XmlParser().parse(File("${dirPath}/AndroidManifest.xml"))

                val usesSdk = (androidManifest["uses-sdk"] as NodeList).first() as Node
                targetSdkVersion = (usesSdk.get("@targetSdkVersion") as Int?) ?: targetSdkVersion
                minSdkVersion = (usesSdk.get("@minSdkVersion") as Int?) ?: minSdkVersion
            }

            // Write Android.bp
            File("$libsBase/Android.bp").let { file ->
                // Add autogenerated header if file is empty
                if (file.length() == 0L) {
                    file.writeText(LIBS_ANDROID_BP_HEADER)
                }

                when (it.extension) {
                    "aar" -> {
                        file.appendText(
                            """

                            android_library_import {
                                name: "${id.aospModuleName}-nodeps",
                                aars: ["${id.aospModulePath}/${it.file.name}"],
                                sdk_version: "$targetSdkVersion",
                                min_sdk_version: "$minSdkVersion",
                                apex_available: [
                                    "//apex_available:platform",
                                    "//apex_available:anyapex",
                                ],
                                static_libs: [%s],
                            }

                            android_library {
                                name: "${id.aospModuleName}",
                                sdk_version: "$targetSdkVersion",
                                min_sdk_version: "$minSdkVersion",
                                apex_available: [
                                    "//apex_available:platform",
                                    "//apex_available:anyapex",
                                ],
                                manifest: "${id.aospModulePath}/AndroidManifest.xml",
                                static_libs: [%s],
                                java_version: "1.7",
                            }

                        """.trimIndent().format(
                                it.formatDependencies(dependencies, false),
                                it.formatDependencies(dependencies, true)
                            )
                        )
                    }

                    "jar" -> {
                        file.appendText(
                            """

                            java_import {
                                name: "${id.aospModuleName}-nodeps",
                                jars: ["${id.aospModulePath}/${it.file.name}"],
                                sdk_version: "$targetSdkVersion",
                                min_sdk_version: "$minSdkVersion",
                                apex_available: [
                                    "//apex_available:platform",
                                    "//apex_available:anyapex",
                                ],
                            }

                            java_library_static {
                                name: "${id.aospModuleName}",
                                sdk_version: "$targetSdkVersion",
                                min_sdk_version: "$minSdkVersion",
                                apex_available: [
                                    "//apex_available:platform",
                                    "//apex_available:anyapex",
                                ],
                                static_libs: [%s],
                                java_version: "1.7",
                            }

                        """.trimIndent().format(
                                it.formatDependencies(dependencies, true)
                            )
                        )
                    }

                    else -> throw Exception("Unknown file extension: ${it.extension}")
                }
            }
        }
    }

    private val ModuleVersionIdentifier.aospModuleName
        get() = "${project.rootProject.name}_${group}_${name}"

    private val String.aospModuleName
        get() = if (contains(":")) {
            val (group, artifactId) = split(":")
            "${project.rootProject.name}_${group}_${artifactId}"
        } else {
            "${project.rootProject.name}_${this}"
        }

    private fun ResolvedArtifact.formatDependencies(
        dependencies: Sequence<String>, addNoDependencies: Boolean
    ): String {
        val id = moduleVersion.id

        val aospDependencies = dependencies.filter { dep ->
            when {
                configuration.resolvedConfiguration.resolvedArtifacts.firstOrNull { artifact ->
                    dep == "${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}"
                } == null -> {
                    val moduleName = if (addNoDependencies) {
                        id.aospModuleName
                    } else {
                        "${id.aospModuleName}-nodeps"
                    }
                    log("$moduleName: Skipping $dep because it's not in resolvedArtifacts")
                    false
                }

                dep == "org.jetbrains.kotlin:kotlin-stdlib-common" -> false
                else -> true
            }
        }.distinct().toMutableList()

        if (addNoDependencies) {
            // Add -nodeps dependency for android_library/java_library_static
            aospDependencies.add(0, "${id.group}_${id.name}-nodeps")
        }

        var ret = ""

        if (aospDependencies.isNotEmpty()) {
            aospDependencies.forEach { dep ->
                ret += if (dep.contains(":")) {
                    val (group, artifactId) = dep.split(":")
                    if (isAvailableInAOSP(group, artifactId)) {
                        "\n${spaces(8)}\"${moduleNameAOSP(dep)}\","
                    } else {
                        "\n${spaces(8)}\"${dep.aospModuleName}\","
                    }
                } else {
                    "\n${spaces(8)}\"${dep.aospModuleName}\","
                }
            }
            ret += "\n${spaces(4)}"
        }

        return ret
    }

    companion object {
        private const val DEBUG = false

        private const val SHARED_LIBS_HEADER = "// DO NOT EDIT THIS SECTION MANUALLY\n"
        private const val LIBS_ANDROID_BP_HEADER = "// DO NOT EDIT THIS FILE MANUALLY"

        private const val DEFAULT_MIN_SDK_VERSION = 14

        private fun log(message: String) {
            if (DEBUG) {
                println(message)
            }
        }

        private fun moduleNameAOSP(moduleName: String) = when (moduleName) {
            "androidx.constraintlayout:constraintlayout" -> "androidx-constraintlayout_constraintlayout"
            "com.google.auto.value:auto-value-annotations" -> "auto_value_annotations"
            "com.google.guava:guava" -> "guava"
            "com.google.guava:listenablefuture" -> "guava"
            "org.jetbrains.kotlin:kotlin-stdlib" -> "kotlin-stdlib"
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8" -> "kotlin-stdlib-jdk8"
            "org.jetbrains.kotlinx:kotlinx-coroutines-android" -> "kotlinx-coroutines-android"
            else -> moduleName.replace(":", "_")
        }

        private fun spaces(n: Int): String {
            var ret = ""
            for (i in n downTo 1) {
                ret += ' '
            }
            return ret
        }
    }
}
