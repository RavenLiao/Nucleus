import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("io.github.kdroidfilter.nucleus")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))
    implementation(project(":system-info"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":decorated-window-jni"))

    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.intellij.icons)
    implementation(libs.jna.jpms)

    implementation(libs.coroutines.core)

    // Lets-Plot charting
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:4.13.0")
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.9.0")
    implementation("org.jetbrains.lets-plot:canvas:4.9.0")
    implementation("org.jetbrains.lets-plot:plot-raster:4.9.0")
    implementation("org.jetbrains.lets-plot:lets-plot-compose-desktop:3.1.0")

    implementation(project(":graalvm-runtime"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

nucleus.application {
    mainClass = "systeminfodemo.MainKt"
    jvmArgs +=
        listOf(
            "--add-opens",
            "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "system-info-demo"
        march = providers.gradleProperty("nativeMarch").getOrElse("native")
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )
    }

    nativeDistributions {
        packageName = "SystemInfoDemo"
        packageVersion = "1.0.0"
    }
}

tasks.withType<Jar> {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
}
