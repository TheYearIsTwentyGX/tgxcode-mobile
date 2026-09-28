// AGP 9 enables built-in Kotlin by default, so `org.jetbrains.kotlin.android` is
// gone — applying it is a hard error against the new DSL. AGP 9.3.1 carries KGP
// 2.2.10; this raises it, which is the documented way to do so, because
// multiplatform-markdown-renderer 0.43.0 is compiled against kotlin-stdlib 2.4.0
// and a 2.2 compiler refuses its metadata.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
