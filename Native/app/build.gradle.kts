import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.big.town.anative"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.big.town.anative"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "3.12.0"

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

    // Флейворы: full = VirtualDisplay/Frida; light = без них.
    flavorDimensions += "tier"
    productFlavors {
        create("full") {
            dimension = "tier"
            buildConfigField("boolean", "IS_FULL", "true")
        }
        create("light") {
            dimension = "tier"
            buildConfigField("boolean", "IS_FULL", "false")
        }
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
// versionName/versionCode and distinguishes Full/Light for the desktop installer.
abstract class VoyahBuildIdentity : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val revision: Property<String>
    @get:Input abstract val component: Property<String>
    @get:Input abstract val tier: Property<String>
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
            "schema" to 1, "product" to "VoyahTune", "component" to component.get(),
            "variant" to tier.get(), "releaseVersion" to releaseVersion.get(),
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
            runtimeAliases.convention(emptyMap())
            val recipePath = providers.gradleProperty("voyahInstallRecipe").orNull
            if (recipePath != null) {
                val recipeFile = file(recipePath)
                recipeFiles.from(recipeFile)
                val recipe = groovy.json.JsonSlurper().parse(recipeFile) as Map<*, *>
                val sources = groovy.json.JsonSlurper().parse(file(providers.gradleProperty("voyahReleaseSources").get())) as Map<*, *>
                val selectedTier = variant.productFlavors.single { it.first == "tier" }.second
                (recipe["files"] as List<*>).map { it as Map<*, *> }.filter {
                    it["artifact"] != "native.apk" && (it["variants"] as List<*>).contains(selectedTier)
                }.forEach { operation ->
                    val source = (sources["artifacts"] as List<*>).map { it as Map<*, *> }.single {
                        it["name"] == operation["artifact"] && it["variant"] == (if (operation["variantArtifact"] == true) selectedTier else null)
                    }
                    val runtimeSource = rootProject.projectDir.parentFile.resolve(source["source"] as String)
                    runtimeFiles.from(runtimeSource)
                    runtimeAliases.put(runtimeSource.canonicalPath, operation["artifact"] as String)
                }
            } else {
            runtimeFiles.from(packaging.resolve("system/privapp-permissions-ru.big.town.anative.xml"))
            if (variant.productFlavors.single { it.first == "tier" }.second == "full") {
                runtimeFiles.from(fileTree(packaging.resolve("inject")) { include("*.js", "*.json") })
                runtimeFiles.from(listOf("load.bin", "voyahtune.load.rc", "voyahtune.load.sh").map { packaging.resolve("system/$it") })
                runtimeFiles.from(packaging.resolve("tools/frida-inject-16.2.1-android-arm64"))
            }
            }
            component.set(android.namespace!!)
            tier.set(variant.productFlavors.single { it.first == "tier" }.second)
            outputDirectory.set(layout.buildDirectory.dir("generated/voyahIdentity/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(identity, VoyahBuildIdentity::outputDirectory)
    }
}
