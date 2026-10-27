import java.util.Properties

// PCDash Remote: the phone you carry.
plugins {
    alias(libs.plugins.android.application)
}

val versionProps = Properties().apply { file("version.properties").inputStream().use { load(it) } }
// CI passes the release key password in PCDASH_KEYSTORE_PASSWORD; local builds keep the default.
val keystorePassword = System.getenv("PCDASH_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
val releaseKeystore = file("pcdash-remote.keystore")

android {
    namespace = "vn.vuumi.pcdash.remote"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "vn.vuumi.pcdash.remote"
        minSdk = 24
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(emptyList<String>())
        }
        getByName("test") {
            java.setSrcDirs(listOf("test"))
        }
        getByName("androidTest") {
            java.setSrcDirs(emptyList<String>())
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = keystorePassword
            keyAlias = "pcdash-remote"
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
            // Stored uncompressed-and-extracted as before (android:extractNativeLibs="true").
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint runs as its own step; it does not stop a release build.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.junit)
}

// The release key is made on the first local build; CI restores the real one from secrets first.
val ensureReleaseKeystore by tasks.registering(Exec::class) {
    onlyIf { !releaseKeystore.exists() }
    val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(
        keytool.absolutePath, "-genkeypair", "-keystore", releaseKeystore.absolutePath, "-alias", "pcdash-remote",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", keystorePassword, "-keypass", keystorePassword, "-dname", "CN=PCDashRemote, O=VuuMi, C=VN",
    )
}
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    dependsOn(ensureReleaseKeystore)
}
