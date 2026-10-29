buildscript {
    dependencies {
        // AGP 9 bundles KGP 2.2.10. Upgrade it here so the Kotlin compiler matches
        // the Compose compiler plugin version declared in gradle/libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
