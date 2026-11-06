plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless")
    alias(libs.plugins.java.module.testing)
}

group = "io.github.hectorespert"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.jspecify)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

spotless {
    java {
        palantirJavaFormat(libs.versions.palantir.get())
        removeUnusedImports()
        licenseHeader("/*\n * Copyright \$YEAR Hector Espert\n * SPDX-License-Identifier: Apache-2.0\n */")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // slf4j-simple also receives the logs of Gradle's test worker, which are noise at INFO.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}

// The internal packages are not exported, so they stay out of the public Javadoc. Their compiled
// classes are patched into the module so that the public sources still resolve them.
tasks.named<Javadoc>("javadoc") {
    exclude("**/internal/**")
    val classes =
        sourceSets.main
            .get()
            .java.destinationDirectory
            .get()
            .asFile
    (options as StandardJavadocDocletOptions).addStringOption("-patch-module", "io.github.hectorespert.jmediasoup=$classes")
}

// Runs the tests inside the library module (--patch-module), so they see the
// internal packages and also check the requires and exports of module-info.java.
javaModuleTesting.whitebox(testing.suites["test"]) {
    requires.add("org.junit.jupiter.api")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
