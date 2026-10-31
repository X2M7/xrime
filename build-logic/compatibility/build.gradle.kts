// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

import org.gradle.api.attributes.Attribute
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.osfans.trime.build_logic"

// Our plugin JARs need no external-plugin backport. Select their original
// project artifacts so Gradle keeps the included-build producer dependencies.
val backport = Attribute.of("com.osfans.trime.gradle-visible-backport", String::class.java)
listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) {
        attributes.attribute(backport, "backported")
    }
}

// Keep audited compiler fixtures off the test runtime classpath. Their paths are
// supplied lazily by Gradle and their actual bytes are verified by patchJar.
val auditedAgp941 = configurations.create("auditedAgp941") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val auditedAgp950 = configurations.create("auditedAgp950") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation("org.ow2.asm:asm-tree:9.9")
    testImplementation(kotlin("test"))
    add(auditedAgp941.name, "com.android.tools.build:gradle:9.4.1")
    add(auditedAgp950.name, "com.android.tools.build:gradle:9.5.0")
}

abstract class AuditedAgpArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val agp941: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val agp950: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        "-Dxrime.test.agp941=${agp941.get().asFile.absolutePath}",
        "-Dxrime.test.agp950=${agp950.get().asFile.absolutePath}",
    )
}

tasks.withType<Test>().configureEach {
    val fixtureArguments = objects.newInstance(AuditedAgpArguments::class.java)
    fixtureArguments.agp941.set(layout.file(auditedAgp941.elements.map { it.single().asFile }))
    fixtureArguments.agp950.set(layout.file(auditedAgp950.elements.map { it.single().asFile }))
    jvmArgumentProviders.add(fixtureArguments)
}

gradlePlugin {
    plugins {
        register("gradleCompatibility") {
            id = "com.osfans.trime.gradle-compatibility"
            implementationClass = "GradleCompatibilityPlugin"
        }
    }
}
