plugins {
    // Applied per module; declared here once so the Kotlin/AGP plugins are not
    // loaded multiple times in sibling subprojects (Gradle requirement).
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
}
