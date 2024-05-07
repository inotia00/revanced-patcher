plugins {
    kotlin("jvm") version "1.9.23"
    `maven-publish`
}

group = "app.revanced"

val githubUsername: String = project.findProperty("gpr.user") as? String ?: System.getenv("GITHUB_ACTOR")
val githubPassword: String = project.findProperty("gpr.key") as? String ?: System.getenv("GITHUB_TOKEN")

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://maven.pkg.github.com/revanced/multidexlib2")
        credentials {
            username = githubUsername
            password = githubPassword
        }
    }
}

dependencies {
    implementation("xpp3:xpp3:1.1.4c")
    implementation("app.revanced:smali:2.5.3-a3836654")
    implementation("app.revanced:multidexlib2:2.5.3-a3836654")
    // ARSCLib fork with a custom zip implementation to fix performance issues on Android devices.
    // The fork will no longer be needed after archive2 is finished upstream (https://github.com/revanced/ARSCLib/issues/2).
    implementation("com.reandroid:arsclib:1.1.7")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.23")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.23")

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
        mavenLocal()
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}
