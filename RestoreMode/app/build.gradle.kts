import java.io.File
import java.security.MessageDigest

//import com.android.build.gradle.internal.dependency.isProguardRule

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.big.town.restoremode"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.big.town.restoremode"
        minSdk = 30
        targetSdk = 35
        val release = providers.gradleProperty("voyahReleaseVersion").orElse("3.14.0").get()
        val parts = release.substringBefore('-').substringBefore('+').split('.').map { it.toInt() }
        require(parts.size == 3 && parts.all { it in 0..999 })
        versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        versionName = release

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            ndk { abiFilters += "arm64-v8a" }
            // Enables code-related app optimization.
            isMinifyEnabled = false

            // Enables resource shrinking.
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

        }
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            // Android instrumentation needs library classes removed from the normal debug APK.
            val minifyDebug = providers.gradleProperty("voyahMinifyDebug").orElse("true").get().toBoolean()
            // Enables code-related app optimization.
            isMinifyEnabled = minifyDebug

            // Enables resource shrinking.
            isShrinkResources = minifyDebug

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
    ndkVersion = "27.0.12077973"

}

dependencies {

    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(files(layout.buildDirectory.file("voice-deps/downloads/sherpa.aar")))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// Pin offline Zipformer2, Silero VAD and DeepFilterNet3 dependencies by SHA-256.
val prepareVoiceDependencies = tasks.register<Exec>("prepareVoiceDependencies") {
    inputs.files(rootProject.file("prepare_voice.py"), rootProject.file("voice-dependencies.json"))
    outputs.dir(layout.buildDirectory.dir("voice-deps/assets"))
    outputs.file(layout.buildDirectory.file("voice-deps/downloads/sherpa.aar"))
    commandLine("python3", rootProject.file("prepare_voice.py"))
}
val prepareVoiceNative = tasks.register<Exec>("prepareVoiceNative") {
    dependsOn(prepareVoiceDependencies)
    inputs.files(rootProject.file("prepare_voice.py"), rootProject.file("voice-dependencies.json"),
        rootProject.file("rust-toolchain.toml"),
        rootProject.fileTree("voice-native") { exclude("target/**") })
    outputs.dir(layout.buildDirectory.dir("voice-deps/jniLibs"))
    commandLine("python3", rootProject.file("prepare_voice.py"), "--ndk",
        android.sdkDirectory.resolve("ndk/${android.ndkVersion}"), "--abis", "arm64-v8a,x86_64")
}
android.sourceSets.getByName("main") {
    assets.srcDir(layout.buildDirectory.dir("voice-deps/assets"))
    jniLibs.srcDir(layout.buildDirectory.dir("voice-deps/jniLibs"))
}
tasks.named("preBuild") { dependsOn(prepareVoiceDependencies, prepareVoiceNative) }

// Release identity travels inside the signed APK. It is independent of Android's
// versionName/versionCode and describes the installed release.
abstract class VoyahBuildIdentity : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val revision: Property<String>
    @get:Input abstract val component: Property<String>
    @get:Input abstract val infrastructure: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val recipeFiles: ConfigurableFileCollection
    @get:Input abstract val runtimeAliases: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val dir = outputDirectory.get().asFile
        dir.mkdirs()
        dir.resolve("voyahtune-build.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
            "schema" to 3, "product" to "VoyahTune", "component" to component.get(),
            "infrastructure" to infrastructure.get(),
            "releaseVersion" to releaseVersion.get(),
            "buildRevision" to revision.get(),
            "recipeSha256" to recipeFiles.files.singleOrNull()?.let { source ->
                MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            },
            "runtimeHashes" to runtimeFiles.files.associate { source ->
                val name = runtimeAliases.get()[source.canonicalPath] ?: when (source.name) {
                    "privapp-permissions-ru.big.town.anative.xml" -> "whitelist.xml"
                    "frida-inject-16.2.1-android-arm64" -> "frida-inject"
                    else -> source.name
                }
                name to MessageDigest.getInstance("SHA-256")
                    .digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            }.toSortedMap()
        )) + "\n")
    }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        val identity = tasks.register<VoyahBuildIdentity>("generate${variant.name.replaceFirstChar { it.uppercase() }}VoyahIdentity") {
            releaseVersion.set(providers.gradleProperty("voyahReleaseVersion").orElse("0.0.0-dev"))
            revision.set(providers.gradleProperty("voyahBuildRevision").orElse("local"))
            val packaging = rootProject.projectDir.parentFile.resolve("Packaging")
            val profile = providers.gradleProperty("voyahInfrastructure").orElse("od").get()
            require(profile == "pi" || profile == "od") { "voyahInfrastructure must be pi or od" }
            infrastructure.set(profile)
            val infra = packaging.resolve(profile)
            runtimeAliases.convention(emptyMap())
            val recipePath = providers.gradleProperty("voyahInstallRecipe").orNull
            if (recipePath != null) {
                val recipeFile = file(recipePath)
                recipeFiles.from(recipeFile)
                val recipe = groovy.json.JsonSlurper().parse(recipeFile) as Map<*, *>
                val sources = groovy.json.JsonSlurper().parse(file(providers.gradleProperty("voyahReleaseSources").get())) as Map<*, *>
                (recipe["files"] as List<*>).map { it as Map<*, *> }.filter {
                    it["artifact"] != "native.apk"
                }.forEach { operation ->
                    val source = (sources["artifacts"] as List<*>).map { it as Map<*, *> }.single {
                        it["name"] == operation["artifact"]
                    }
                    val runtimeSource = rootProject.projectDir.parentFile.resolve(source["source"] as String)
                    runtimeFiles.from(runtimeSource)
                    runtimeAliases.put(runtimeSource.canonicalPath, operation["artifact"] as String)
                }
            } else {
            runtimeFiles.from(packaging.resolve("system/privapp-permissions-ru.big.town.anative.xml"))
            run {
                runtimeFiles.from(fileTree(infra.resolve("inject")) { include("*.js", "*.json") })
                runtimeFiles.from(listOf("voyahtune.load.rc", "voyahtune.load.sh").map { infra.resolve("system/$it") })
                if (profile == "od") runtimeFiles.from(infra.resolve("system/load.bin"))
                runtimeFiles.from(infra.resolve("tools/frida-inject-16.2.1-android-arm64"))
            }
            }
            component.set(android.namespace!!)
            outputDirectory.set(layout.buildDirectory.dir("generated/voyahIdentity/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(identity, VoyahBuildIdentity::outputDirectory)
    }
}

android.sourceSets.getByName("main").java.srcDir(rootProject.projectDir.parentFile.resolve("SharedAndroid/src/main/java"))
