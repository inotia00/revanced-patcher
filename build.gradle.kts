plugins {
    kotlin("jvm") version "1.8.22"
    `maven-publish`
    signing
}

group = "io.github.inotia00"

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
}

dependencies {
    implementation("xpp3:xpp3:1.1.4c")
    implementation("com.android.tools.smali:smali:3.0.3")
    implementation("com.google.guava:guava:32.1.1-android")
    implementation("io.github.inotia00:apktool-lib:2.7.2-SNAPSHOT")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.8.22")
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
    jvmToolchain(11)
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

publishing {
    repositories {
        val sonatypeUsername = System.getenv("SONATYPE_USERNAME")
        val sonatypePassword = System.getenv("SONATYPE_PASSWORD")

        if (sonatypeUsername != null && sonatypePassword != null) {
            repositories {
                maven {
                    url = if (project.version.toString().contains("SNAPSHOT")) {
                        uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    } else {
                        uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    }
                    credentials {
                        username = sonatypeUsername
                        password = sonatypePassword
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
