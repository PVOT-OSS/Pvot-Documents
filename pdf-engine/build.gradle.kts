import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.prauga.pdfengine"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main").jniLibs.srcDirs("build/rustJniLibs")
    }
}

val rustDir = file("src/main/rust")
val rustOutDir = layout.buildDirectory.dir("rustJniLibs")

// Debug builds only need the device's own ABI (arm64-v8a on the test phone); release builds
// cover the ABIs real users plausibly run on. `cargo ndk` needs ANDROID_NDK_HOME — set by the
// environment, or discovered under $ANDROID_HOME/ndk/<highest installed version> otherwise.
fun ndkHome(): String {
    System.getenv("ANDROID_NDK_HOME")?.let { return it }
    val sdkRoot = System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    val ndkRoot = file("$sdkRoot/ndk")
    val newest = ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: error("No Android NDK found under $ndkRoot; set ANDROID_NDK_HOME.")
    return newest.absolutePath
}

fun registerCargoNdkTask(name: String, abis: List<String>) {
    tasks.register<Exec>(name) {
        workingDir = rustDir
        environment("ANDROID_NDK_HOME", ndkHome())
        val args = mutableListOf("ndk")
        abis.forEach { args += listOf("-t", it) }
        args += listOf("-o", rustOutDir.get().asFile.absolutePath, "build", "--release")
        commandLine("cargo", *args.toTypedArray())
        inputs.dir(rustDir.resolve("src"))
        inputs.file(rustDir.resolve("Cargo.toml"))
        outputs.dir(rustOutDir)
    }
}

registerCargoNdkTask("cargoNdkBuildDebug", listOf("arm64-v8a"))
registerCargoNdkTask("cargoNdkBuildRelease", listOf("arm64-v8a", "armeabi-v7a", "x86_64"))

tasks.named("preBuild") {
    dependsOn(if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
        "cargoNdkBuildRelease"
    } else {
        "cargoNdkBuildDebug"
    })
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.graphics)
}
