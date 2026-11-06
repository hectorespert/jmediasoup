plugins {
    base
    alias(libs.plugins.spotless)
}

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
