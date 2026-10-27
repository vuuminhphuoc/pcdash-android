// Code both apps use (UI helpers, app lock, pairing, i18n, the tsnet bridge, QR) and the native
// libraries: libpcnet.so (Go, embedded Tailscale) and libpcnet_jni.so, built by pcnet/build-android.ps1.
plugins {
    alias(libs.plugins.android.library)
}

val pcnetOut = layout.buildDirectory.dir("pcnet/native")
val pcnetJniLibs = layout.buildDirectory.dir("pcnet/jniLibs")

android {
    namespace = "vn.vuumi.pcdash.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 24
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(emptyList<String>())
            assets.setSrcDirs(emptyList<String>())
            jniLibs.setSrcDirs(listOf(pcnetJniLibs.get().asFile))
        }
        getByName("test") {
            java.setSrcDirs(listOf("test"))
        }
        getByName("androidTest") {
            java.setSrcDirs(emptyList<String>())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Decodes and draws QR codes (setup code on the host, pairing code on the remote).
    api(libs.zxing.core)
    testImplementation(libs.junit)
}

// Go + NDK on Windows (the script uses the Windows NDK toolchain). Pass -Ppcdash.skipNative=true to
// build without the native libraries, for example on another OS or for unit tests.
val skipNative = providers.gradleProperty("pcdash.skipNative").map { it != "false" }.getOrElse(false)

val buildPcnet by tasks.registering(Exec::class) {
    description = "Builds libpcnet.so and libpcnet_jni.so for arm64-v8a and armeabi-v7a."
    onlyIf { !skipNative }
    inputs.files(fileTree(rootProject.file("pcnet")) {
        include("*.go", "go.mod", "go.sum", "build-android.ps1")
    }).withPropertyName("goSources")
    inputs.files(fileTree("jni"), fileTree(rootProject.file("remote/jni"))).withPropertyName("jniSources")
    outputs.dir(pcnetOut)
    workingDir = rootDir
    commandLine(
        "pwsh", "-NoProfile", "-File", rootProject.file("pcnet/build-android.ps1").absolutePath,
        "-OutputRoot", pcnetOut.get().asFile.absolutePath,
    )
}

// Only the .so files go into the APKs (cgo also writes a C header next to them).
val stagePcnetLibs by tasks.registering(Sync::class) {
    dependsOn(buildPcnet)
    from(pcnetOut) {
        include("**/*.so")
    }
    into(pcnetJniLibs)
}

tasks.named("preBuild") {
    dependsOn(stagePcnetLibs)
}
