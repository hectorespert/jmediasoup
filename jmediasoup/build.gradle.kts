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

    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
