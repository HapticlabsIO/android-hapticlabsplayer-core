import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jreleaser.gradle.plugin.tasks.JReleaserDeployTask
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    alias(libs.plugins.jreleaser)
}

description = "What Hapticlabs' Android players share: the device's haptic capabilities and effects, " +
        "and audio and OGG playback in sync with what plays along"

val repositoryPath = "github.com/HapticlabsIO/android-hapticlabsplayer-core"

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// `publish` stages the artifacts here, and JReleaser deploys them from here
val stagingDeployDir = layout.buildDirectory.dir("staging-deploy")

android {
    namespace = "io.hapticlabs.hapticlabsplayer.core"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 24
        aarMetadata {
            // The level of the types in the public API, such as HapticCapabilities'. 37.2 is only
            // needed internally, behind version checks, so apps don't have to compile against it
            minCompileSdk = 36
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            pom {
                name = "Hapticlabs Player core"
                description = project.description
                url = "https://$repositoryPath"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://$repositoryPath/blob/main/LICENSE.txt"
                    }
                }
                developers {
                    developer {
                        id = "robot-controller"
                        name = "Michi"
                    }
                }
                scm {
                    connection = "scm:git:git://$repositoryPath.git"
                    developerConnection = "scm:git:git://$repositoryPath.git"
                    url = "https://$repositoryPath"
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        maven {
            url = stagingDeployDir.get().asFile.toURI()
        }
    }
}

jreleaser {
    gitRootSearch = true
    project {
        copyright = "Copyright (c) 2026 Hapticlabs GmbH"
    }
    signing {
        active = Active.ALWAYS
        armored = true
    }
    deploy {
        maven {
            mavenCentral.create("sonatype") {
                active = Active.ALWAYS
                url = "https://central.sonatype.com/api/v1/publisher"
                stagingRepository(stagingDeployDir.get().toString())
                setAuthorization("Basic")
                namespace = "io.hapticlabs"
                applyMavenCentralRules = false
                sign = true
                checksums = true
                sourceJar = true
                javadocJar = true
                retryDelay = 60
            }
        }
    }
}

// Without this, Gradle may run the deploy before `publish` has staged anything
tasks.withType<JReleaserDeployTask>().configureEach {
    dependsOn(tasks.named("publish"))
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.mediarouter)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
