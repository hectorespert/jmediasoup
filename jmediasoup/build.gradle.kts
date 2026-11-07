plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
    alias(libs.plugins.nmcp.aggregation)
    id("com.diffplug.spotless")
    alias(libs.plugins.java.module.testing)
}

group = "io.github.hectorespert"
version = rootProject.version

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

    // Media sources for the tests that send, shared by test and integrationTest and not published.
    testFixturesApi(libs.webrtc.java)

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
    withSourcesJar()
    withJavadocJar()
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
// classes are patched into the module so that the public sources still resolve them. The types of the
// JDK and webrtc-java link to their Javadoc, and any warning fails the build.
tasks.named<Javadoc>("javadoc") {
    exclude("**/internal/**")
    val classes =
        sourceSets.main
            .get()
            .java.destinationDirectory
            .get()
            .asFile
    val webrtcJavaVersion =
        libs.versions.webrtc.java
            .get()
    with(options as StandardJavadocDocletOptions) {
        addStringOption("-patch-module", "io.github.hectorespert.jmediasoup=$classes")
        links(
            "https://docs.oracle.com/en/java/javase/21/docs/api/",
            "https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/$webrtcJavaVersion/",
        )
        addBooleanOption("Xwerror", true)
    }
}

// Integration tests against a mediasoup server in a container, through the public API. They run on
// the classpath, as most applications use the library, while the unit tests cover the module path.
// Without Docker (or the podman socket), or outside Linux, they are skipped.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.jupiter)
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(libs.testcontainers)
                implementation(libs.jackson3.databind)
                runtimeOnly(libs.slf4j.simple)
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtc.java.get()}:$webrtcNativeClassifier")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
                }
            }
        }
    }
}

javaModuleTesting.classpath(testing.suites["integrationTest"])

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

// Runs the tests inside the library module (--patch-module), so they see the
// internal packages and also check the requires and exports of module-info.java.
javaModuleTesting.whitebox(testing.suites["test"]) {
    requires.add("com.fasterxml.jackson.databind")
    requires.add("io.github.hectorespert.jmediasoup.testfixtures")
    requires.add("org.junit.jupiter.api")
    requires.add("org.junit.jupiter.params")
    requires.add("tools.jackson.databind")
}

// The test fixtures are only for the tests of this project.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

// The licence and the notice, with the licences of the ported projects, go in every jar, as MIT and ISC
// require them in every copy.
val legalFiles = rootProject.layout.projectDirectory.files("LICENSE", "NOTICE")
tasks.withType<Jar>().configureEach {
    metaInf {
        from(legalFiles)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "jmediasoup"
                description = "Java client for mediasoup"
                url = "https://github.com/hectorespert/jmediasoup"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "hectorespert"
                        name = "Hector Espert"
                        url = "https://github.com/hectorespert"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/hectorespert/jmediasoup.git"
                    developerConnection = "scm:git:ssh://git@github.com/hectorespert/jmediasoup.git"
                    url = "https://github.com/hectorespert/jmediasoup"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/hectorespert/jmediasoup/issues"
                }
            }
        }
    }
}

// Signs the publication with the armored key in the signingInMemoryKey property, and its passphrase in
// signingInMemoryKeyPassword. If the key holds only a signing subkey, without the secret of the primary
// key, signingInMemoryKeyId names it (its last 8 hex digits). Without the key, a snapshot is published
// unsigned, but a release fails, so that nothing reaches Maven Central unsigned. Empty values count as
// absent, as CI passes them when a secret is not set.
fun signingProperty(name: String) = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val signingKey = signingProperty("signingInMemoryKey")
val signingKeyId = signingProperty("signingInMemoryKeyId")
val signingKeyPassword = signingProperty("signingInMemoryKeyPassword")
val isSnapshot = version.toString().endsWith("-SNAPSHOT")
signing {
    if (signingKey != null) {
        if (signingKeyId != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingKeyPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingKeyPassword)
        }
    }
    isRequired = signingKey != null || !isSnapshot
    sign(publishing.publications["maven"])
}

// Uploads the signed publication to the Central Portal (publishAggregationToCentralPortal), with the
// token of the portal in the mavenCentralUsername and mavenCentralPassword properties. A deployment that
// passes the validation of the portal is published without a manual step. The aggregation is in this
// project, because the root project has the same name, which nmcp does not allow.
nmcpAggregation {
    centralPortal {
        username = providers.gradleProperty("mavenCentralUsername")
        password = providers.gradleProperty("mavenCentralPassword")
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(path))
}

// A release runs build and the upload in the same invocation; the upload waits for build, so that a
// failing test stops it before anything reaches Maven Central, where a version cannot be removed.
tasks.named("nmcpPublishAggregationToCentralPortal") {
    mustRunAfter(tasks.named("build"))
}
