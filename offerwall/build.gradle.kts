import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    `maven-publish`
}

val sdkVersion = "2.0.1"

group = "com.moonetix"
version = sdkVersion

android {
    namespace = "com.moonetix.offerwall"
    compileSdk = 36
    resourcePrefix = "moonetix_"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        // Host apps may compile against any SDK level. Without this, AGP 9 stamps
        // this module's own compileSdk into the AAR as the minimum for every app.
        aarMetadata {
            minCompileSdk = 1
        }
    }

    buildTypes {
        release {
            // Shipped unminified; the host app's R8 shrinks it using consumer-rules.pro.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        targetSdk = 36
        unitTests.isReturnDefaultValues = false
    }

    lint {
        targetSdk = 36
        abortOnError = true
        checkReleaseBuilds = true
        // Version-freshness checks need network lookups and the versions are pinned on purpose.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
        textReport = true
        htmlReport = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // The published POM asks for this kotlin-stdlib, and the classes are written for
    // this language level, so apps on older Kotlin releases can still use the SDK.
    coreLibrariesVersion = "2.1.0"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Real Java default methods, so Java hosts only override the callbacks they need.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json; the real implementation for JVM unit tests.
    testImplementation("org.json:json:20260814")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.moonetix"
            artifactId = "offerwall"
            version = sdkVersion
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Moonetix Offerwall")
                description.set("Android SDK that opens the Moonetix offerwall in a hardened WebView using a single-use session token issued by your server.")
                url.set("https://docs.moonetix.com/android-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("Moonetix")
                    url.set("https://moonetix.com")
                }
                developers {
                    developer {
                        id.set("moonetix")
                        name.set("Moonetix")
                        url.set("https://moonetix.com")
                    }
                }
                scm {
                    url.set("https://docs.moonetix.com/android-sdk")
                }
            }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
