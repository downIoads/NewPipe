/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://repo.clojars.org")
    }
}
include (":app")

// If a local copy of NewPipe Extractor exists next to this project, use it automatically.
// Otherwise, the remote dependency from JitPack is used.

if (file("../NewPipeExtractor").isDirectory) {
    includeBuild("../NewPipeExtractor") {
        dependencySubstitution {
            substitute(module("com.github.downIoads:NewPipeExtractor"))
                .using(project(":extractor"))
        }
    }
}
