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

// The webrtc-java natives for the platform that runs the tests. Users declare their own (0.5.7).
val webrtcNativeClassifier: String =
    run {
        val os = providers.systemProperty("os.name").get().lowercase()
        val arch = providers.systemProperty("os.arch").get().lowercase()
        val osPart =
            when {
                os.startsWith("linux") -> "linux"
                os.startsWith("mac") -> "macos"
                os.startsWith("windows") -> "windows"
                else -> error("webrtc-java has no natives for $os")
            }
        val archPart =
            when (arch) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                "arm" -> "aarch32"
                else -> error("webrtc-java has no natives for $arch")
            }
        "$osPart-$archPart"
    }

dependencies {
    api(libs.jackson.annotations)
    api(libs.jspecify)
    api(libs.webrtc.java)
    implementation(libs.slf4j.api)

    testImplementation(libs.jackson2.databind)
    testImplementation(libs.jackson3.databind)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly(variantOf(libs.webrtc.java) { classifier(webrtcNativeClassifier) })
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

// The module descriptor records the version, which the library reads at runtime (RemoteSdp writes it
// in the origin of the SDP).
val moduleVersion = version.toString()
tasks.named<JavaCompile>("compileJava") {
    options.javaModuleVersion = moduleVersion
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // slf4j-simple also receives the logs of Gradle's test worker, which are noise at INFO.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    // Some webrtc-java events only have package-private constructors; the tests create them by
    // reflection to simulate the stack.
    jvmArgs("--add-opens", "webrtc.java/dev.onvoid.webrtc=io.github.hectorespert.jmediasoup")
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
    requires.add("com.fasterxml.jackson.databind")
    requires.add("org.junit.jupiter.api")
    requires.add("org.junit.jupiter.params")
    requires.add("tools.jackson.databind")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
