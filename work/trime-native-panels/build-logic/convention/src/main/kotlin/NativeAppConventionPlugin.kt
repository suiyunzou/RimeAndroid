/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("unused")
class NativeAppConventionPlugin : NativeBaseConventionPlugin() {
    private val Project.librimeVersion: String
        get() = if (file("prebuilt/arm64-v8a/librime_jni.so").isFile) "official-3.3.13-prebuilt" else
            runCmd(
                "git -C app/src/main/jni/librime describe " +
                    "--tags --long --always --exclude=latest",
            )

    private val Project.openccVersion: String
        get() = if (file("prebuilt/arm64-v8a/librime_jni.so").isFile) "official-3.3.13-prebuilt" else
            runCmd(
                "git -C app/src/main/jni/librime/deps/opencc describe --tags --long --always",
            )

    override fun apply(target: Project) {
        super.apply(target)

        target.pluginManager.apply("com.android.application")

        target.extensions.configure<ApplicationExtension> {
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                    if (target.file("prebuilt/arm64-v8a/librime_jni.so").isFile) keepDebugSymbols.add("**/*.so")
                }
            }
            defaultConfig {
                buildConfigField("String", "LIBRIME_VERSION", "\"${target.librimeVersion}\"")
                buildConfigField("String", "OPENCC_VERSION", "\"${target.openccVersion}\"")
            }
        }
    }
}
