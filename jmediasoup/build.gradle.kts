plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless")
}

group = "io.github.hectorespert"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
