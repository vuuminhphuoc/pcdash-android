import java.security.MessageDigest
import java.util.Properties

// PCDash host: the always-on phone at home.
plugins {
    alias(libs.plugins.android.application)
}

val versionProps = Properties().apply { file("version.properties").inputStream().use { load(it) } }
// CI passes the release key password in PCDASH_KEYSTORE_PASSWORD; local builds keep the default.
val keystorePassword = System.getenv("PCDASH_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
val releaseKeystore = file("pcdash.keystore")
// The remote APK and its manifest, served to the remote for self-update (see AndroidUpdateAssetSource).
val remoteUpdateDir = layout.buildDirectory.dir("generated/remoteUpdate")

android {
    namespace = "vn.vuumi.pcdash"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "vn.vuumi.pcdash"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets", remoteUpdateDir.get().asFile))
        }
        // Host tests run from scripts/test-host-*.ps1.
        getByName("test") {
            java.setSrcDirs(emptyList<String>())
        }
        getByName("androidTest") {
            java.setSrcDirs(emptyList<String>())
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = keystorePassword
            keyAlias = "pcdash"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":shared"))
}

val ensureReleaseKeystore by tasks.registering(Exec::class) {
    onlyIf { !releaseKeystore.exists() }
    val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(
        keytool.absolutePath, "-genkeypair", "-keystore", releaseKeystore.absolutePath, "-alias", "pcdash",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", keystorePassword, "-keypass", keystorePassword, "-dname", "CN=PCDash, O=VuuMi, C=VN",
    )
}
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    dependsOn(ensureReleaseKeystore)
}

// Puts the release remote APK into the host's assets with {versionCode, versionName, sha256, size}.
val bundleRemoteUpdate by tasks.registering {
    description = "Copies the release PCDash Remote APK into the host's assets for self-update."
    dependsOn(":remote:assembleRelease")
    val remoteApk = rootProject.file("remote/build/outputs/apk/release/remote-release.apk")
    val remoteVersion = rootProject.file("remote/version.properties")
    inputs.file(remoteApk)
    inputs.file(remoteVersion)
    outputs.dir(remoteUpdateDir)
    doLast {
        val out = remoteUpdateDir.get().asFile
        out.mkdirs()
        val apk = File(out, "remote-update.apk")
        remoteApk.copyTo(apk, overwrite = true)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        val props = Properties().apply { remoteVersion.inputStream().use { load(it) } }
        val code = props.getProperty("versionCode").trim().toInt()
        val name = props.getProperty("versionName").trim()
        File(out, "remote-update.json").writeText(
            "{\"versionCode\":$code,\"versionName\":\"$name\",\"sha256\":\"$sha256\",\"size\":${apk.length()}}"
        )
        // Copies the old build left in assets/ would clash with these.
        file("assets/remote-update.apk").delete()
        file("assets/remote-update.json").delete()
    }
}

tasks.named("preBuild") {
    dependsOn(bundleRemoteUpdate)
}
