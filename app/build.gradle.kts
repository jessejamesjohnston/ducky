import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Open-source notices screen: generates res/raw/aboutlibraries.json per variant.
    alias(libs.plugins.aboutlibraries.android)
    jacoco
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Release signing values, first match wins: local.properties (`ducky.keystore.<name>`), a Gradle
 * property of the same name, or the environment (`DUCKY_KEYSTORE_<NAME>`, which is what
 * scripts/release.sh uses so no password is written to disk). Names: path, storePassword,
 * keyAlias, keyPassword.
 */
fun signingProp(name: String): String? =
    localProps.getProperty("ducky.keystore.$name")
        ?: providers.gradleProperty("ducky.keystore.$name").orNull
        ?: System.getenv("DUCKY_KEYSTORE_" + name.uppercase())

android {
    namespace = "com.merrimentlabs.ducky"
    compileSdk = 36

    defaultConfig {
        // Publisher: Merriment Labs. The app id is permanent once published. The code namespace
        // above is the same name (it names code packages and components).
        applicationId = "com.merrimentlabs.ducky"
        minSdk = 34
        targetSdk = 36
        versionCode = 37
        versionName = "0.28.1"

        // Diagnostic build for widget glitches (a jumping clock or uneven water on a launcher):
        // -PwidgetHeartbeat=false builds an APK whose display tick still runs (the notification
        // keeps re-posting its progress) but never re-renders the widgets, so only the
        // launcher-run Chronometer moves. Run the same timer at the same widget size in both
        // builds: a glitch that remains in this one comes from the launcher, not from Ducky's
        // re-renders. Releases and CI always build the default (heartbeat on).
        buildConfigField(
            "boolean", "WIDGET_HEARTBEAT",
            (providers.gradleProperty("widgetHeartbeat").orNull != "false").toString()
        )
    }

    signingConfigs {
        create("release") {
            // The release key comes from signingProp above. Absent keystore = release builds
            // FAIL (see the preReleaseBuild gate below) unless -PallowDebugSigning=true is
            // passed explicitly.
            val storePath = signingProp("path")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingProp("storePassword")
                keyAlias = signingProp("keyAlias")
                keyPassword = signingProp("keyPassword")
            }
        }
    }

    val releaseKeystorePresent = signingConfigs.getByName("release").storeFile != null
    val allowDebugSigning = providers.gradleProperty("allowDebugSigning").orNull == "true"

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The debug fallback is only wired so the variant CONFIGURES on a
            // keystore-less machine (debug/test tasks must stay green); whether
            // a release artifact may actually be BUILT with it is gated at task
            // time below, so the failure only fires when release is requested.
            signingConfig =
                if (releaseKeystorePresent) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    // Fail-closed release signing: a debug-signed "release" APK is unshippable,
    // and a silent fallback to the debug key would make that easy to miss. First task of the
    // release chain, so nothing is minified before the gate trips.
    tasks.configureEach {
        if (name == "preReleaseBuild" && !releaseKeystorePresent) {
            doFirst {
                if (allowDebugSigning) {
                    logger.warn(
                        "WARNING: release keystore missing: building release with DEBUG " +
                            "signing because -PallowDebugSigning=true. Not for distribution."
                    )
                } else {
                    error(
                        "release signing requires the keystore: run scripts/release.sh, or set " +
                            "ducky.keystore.* in local.properties or DUCKY_KEYSTORE_* in the " +
                            "environment (pass -PallowDebugSigning=true for a local R8 test build)"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        // Zero warnings is the bar: a new warning fails the build. A check that is switched off
        // or suppressed says why where it is (app/lint.xml, or a comment beside the suppression).
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }
}

/**
 * Fails the build when a merged manifest requests a permission outside [allowed], and always when
 * it requests a network permission. Ducky's privacy promise is that it cannot go online, and
 * libraries can add permissions through manifest merging without any change to Ducky's own
 * manifest, so the check reads the merged manifest of each variant, which is what the APK carries.
 */
abstract class CheckManifestPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    @TaskAction
    fun check() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val requested = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map {
                (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(androidNs, "name")
            }
        }.toSortedSet()
        val network = requested.filter { NETWORK.containsMatchIn(it) }
        if (network.isNotEmpty()) {
            throw GradleException(
                "Network permission in the merged manifest: ${network.joinToString()}. Ducky must " +
                    "not be able to go online; remove it with tools:node=\"remove\" in " +
                    "app/src/main/AndroidManifest.xml (the merger report in app/build/outputs/logs " +
                    "names the library that added it)."
            )
        }
        val unexpected = requested - allowed.get()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Unexpected permission in the merged manifest: ${unexpected.joinToString()}. " +
                    "Remove it, or add it to the allowed list in app/build.gradle.kts after review."
            )
        }
    }

    private companion object {
        val NETWORK = Regex("INTERNET|NETWORK|WIFI", RegexOption.IGNORE_CASE)
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val checkTask = tasks.register<CheckManifestPermissions>("check${cap}ManifestPermissions") {
            group = "verification"
            description = "Fails if the ${variant.name} merged manifest requests an unexpected or " +
                "network permission."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowed.addAll(
                "android.permission.USE_EXACT_ALARM",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.POST_PROMOTED_NOTIFICATIONS",
                "android.permission.USE_FULL_SCREEN_INTENT",
                "android.permission.VIBRATE",
                "android.permission.WAKE_LOCK",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            )
            // AndroidX core declares this app-private signature permission to protect receivers
            // registered with RECEIVER_NOT_EXPORTED on older Android versions.
            allowed.add(variant.applicationId.map { "$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
        }
        // Every APK, debug or release, goes through the check.
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(checkTask) }
    }
}

// Open-source notices screen (Settings > About). The .android plugin writes
// res/raw/aboutlibraries.json for each variant from the RUNTIME classpath, so the
// release file lists exactly what ships (no test libraries). LicensesScreen reads it.
aboutLibraries {
    // The build never fetches license texts from the network (reproducible output).
    offlineMode = true
    collect {
        // License texts are vendored in aboutlibraries/licenses/ (offline mode has no
        // other source). BSD-3-Clause there is protobuf's own LICENSE, with Google's
        // copyright line, rather than the SPDX template that has none.
        configPath = file("aboutlibraries")
        // BOMs are version constraints, not code that ships.
        includePlatform = false
    }
    export {
        excludeFields.addAll("funding", "scm")
    }
    license {
        // A dependency under any other license fails the build, so a new one is a
        // deliberate review, not a silent addition to the screen.
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0")
        // Only the repackaged protobuf runtimes may be BSD: the vendored BSD text is theirs.
        allowedLicensesMap = mapOf(
            // The bundled interface typeface (res/font/figtree.ttf), declared in
            // aboutlibraries/libraries/figtree.json since it is a file, not a dependency.
            "OFL-1.1" to listOf("figtree"),
            "BSD-3-Clause" to listOf(
                "androidx.datastore:datastore-preferences-external-protobuf",
                "androidx.glance:glance-appwidget-external-protobuf",
            )
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // Custom Tabs: links from About open in an in-app browser tab on phones and tablets.
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Hosts createComposeRule()'s empty activity; debug manifest only, never in release.
    debugImplementation(libs.compose.ui.test.manifest)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        // Zero compiler warnings, the same bar as lint: a new warning in app or test code fails
        // the build. A suppression says why beside it.
        allWarningsAsErrors = true
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric's API 36 runtime reads FileDescriptor internals through SharedSecrets (JDK 17+).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    // Robolectric loads app classes through its own classloader; without this the agent skips
    // them and every Robolectric-tested class reads as 0%.
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
