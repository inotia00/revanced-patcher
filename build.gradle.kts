plugins {
    kotlin("jvm") version "2.0.0"
    `maven-publish`
    signing
}

group = "io.github.inotia00"

val githubUsername: String = project.findProperty("gpr.user") as? String ?: System.getenv("GITHUB_ACTOR")
val githubPassword: String = project.findProperty("gpr.key") as? String ?: System.getenv("GITHUB_TOKEN")

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
    maven { url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2") }
    maven {
        url = uri("https://maven.pkg.github.com/revanced/smali")
        credentials {
            username = githubUsername
            password = githubPassword
        }
    }
}

dependencies {
    implementation("xpp3:xpp3:1.1.4c")
    implementation("app.revanced:smali:2.5.3-a3836654")
    implementation("io.github.inotia00:multidexlib2:2.5.3-a3836654-SNAPSHOT")
    // ARSCLib fork with a custom zip implementation to fix performance issues on Android devices.
    // The fork will no longer be needed after archive2 is finished upstream (https://github.com/revanced/ARSCLib/issues/2).
    implementation("io.github.reandroid:ARSCLib:1.1.7")

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.0")

    compileOnly("com.google.android:android:4.1.1.4")
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("PASSED", "SKIPPED", "FAILED")
        }
    }
    processResources {
        expand("projectVersion" to project.version)
    }
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    repositories {
        val ossrhToken = System.getenv("OSSRH_TOKEN")
        val ossrhPassword = System.getenv("OSSRH_PASSWORD")

        if (ossrhToken != null && ossrhPassword != null) {
            repositories {
                maven {
                    url = if (project.version.toString().contains("SNAPSHOT")) {
                        uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    } else {
                        uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    }
                    credentials {
                        username = ossrhToken
                        password = ossrhPassword
                    }
                }
            }
        } else
            mavenLocal()
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}

signing {
    if (
        System.getenv("GPG_KEY_ID") == null
        || System.getenv("GPG_KEY") == null
        || System.getenv("GPG_KEY_PASSWORD") == null
    ) return@signing
    useInMemoryPgpKeys(
        System.getenv("GPG_KEY_ID"),
        System.getenv("GPG_KEY"),
        System.getenv("GPG_KEY_PASSWORD"),
    )
    sign(publishing.publications)
}
