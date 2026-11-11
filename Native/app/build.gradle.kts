import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

apply(from = rootProject.file("../SharedAndroid/release-versions.gradle"))
val voyahReleaseVersion: Provider<String> by extra
val voyahMinimumInstallerVersion: Provider<String> by extra

android {
    namespace = "ru.big.town.anative"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.big.town.anative"
        minSdk = 30
        targetSdk = 35
        val release = voyahReleaseVersion.get()
        val parts = release.substringBefore('-').substringBefore('+').split('.').map { it.toInt() }
        require(parts.size == 3 && parts.all { it in 0..999 })
        versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        versionName = release

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    ndkVersion = "27.0.12077973"
    buildToolsVersion = "35.0.0"
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.legacy.support.v4)
    implementation(libs.legacy.support.v13)
    implementation(files("lib/android.car.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    val sdkDir = project.android.sdkDirectory.canonicalPath
    val androidCarJar = "$sdkDir/platforms/android-35/optional/android.car.jar"
}

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
    @get:Input abstract val payloadFormat: Property<Int>
    @get:Input abstract val minimumInstallerVersion: Property<String>
    @get:Input abstract val runtimeAliases: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val dir = outputDirectory.get().asFile
        dir.mkdirs()
        dir.resolve("voyahtune-build.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
            "schema" to 3, "payloadFormat" to payloadFormat.get(), "minimumInstallerVersion" to minimumInstallerVersion.get(), "product" to "VoyahTune", "component" to component.get(),
            "infrastructure" to infrastructure.get(),
            "releaseVersion" to releaseVersion.get(),
            "buildRevision" to revision.get(),
            "recipeSha256" to recipeFiles.files.singleOrNull()?.let { source ->
                MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            },
            "runtimeHashes" to runtimeFiles.files.associate { source ->
                val name = runtimeAliases.get()[source.canonicalPath] ?: when (source.name) {
                    "privapp-permissions-ru.big.town.anative.xml" -> "whitelist.xml"
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
            releaseVersion.set(voyahReleaseVersion)
            revision.set(providers.gradleProperty("voyahBuildRevision").orElse("local"))
            val packaging = rootProject.projectDir.parentFile.resolve("Packaging")
            // Signed APK identity retains the existing shared Android contract.
            infrastructure.set("unified")
            val runtime = packaging.resolve("new")
            payloadFormat.set(providers.gradleProperty("voyahPayloadFormat").map { it.toInt() }.orElse(5))
            minimumInstallerVersion.set(voyahMinimumInstallerVersion)
            runtimeAliases.convention(emptyMap())
            val recipePath = providers.gradleProperty("voyahInstallRecipe").orNull
            if (recipePath != null) {
                val recipeFile = file(recipePath)
                recipeFiles.from(recipeFile)
                val recipe = groovy.json.JsonSlurper().parse(recipeFile) as Map<*, *>
                val sources = groovy.json.JsonSlurper().parse(file(providers.gradleProperty("voyahReleaseSources").get())) as Map<*, *>
                val recipeArtifacts = (recipe["files"] as List<*>) + (recipe["packages"] as List<*>)
                recipeArtifacts.map { it as Map<*, *> }.filter {
                    it["artifact"] != "native.apk" && it["artifact"] != "restore_mode.apk"
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
                runtimeFiles.from(fileTree(runtime) {
                    include(
                        "java/src/main/java/**/*.java", "native/**", "loader/**/*.go", "loader/go.mod",
                        "config/*.json", "system/*"
                    )
                    exclude("**/*_test.go")
                })
            }
            component.set(android.namespace!!)
            outputDirectory.set(layout.buildDirectory.dir("generated/voyahIdentity/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(identity, VoyahBuildIdentity::outputDirectory)
    }
}

android.sourceSets.getByName("main").java.srcDir(rootProject.projectDir.parentFile.resolve("SharedAndroid/src/main/java"))
