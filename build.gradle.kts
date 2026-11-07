plugins {
    base
    alias(libs.plugins.axion.release)
    alias(libs.plugins.spotless)
}

// The version comes from git: a vX.Y.Z tag gives X.Y.Z, and any other commit the next minor version of
// the last tag as a snapshot (0.1.0-SNAPSHOT before the first tag).
scmVersion {
    versionIncrementer("incrementMinor")
}
version = scmVersion.version

repositories {
    mavenCentral()
}

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "gradle.properties", "gradle/*.toml", ".github/**/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
