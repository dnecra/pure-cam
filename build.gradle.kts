buildscript {
    dependencies {
        // Pin the Kotlin compiler used by the Android application plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
