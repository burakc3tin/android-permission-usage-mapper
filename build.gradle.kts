plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "dev.apum"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.apum.MainKt")
    applicationName = "apum"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("serve") {
    group = "application"
    description = "Starts the local APUM web launcher on 127.0.0.1"
    mainClass.set("dev.apum.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    val port = providers.gradleProperty("port").orNull
    args = if (port.isNullOrBlank()) listOf("--serve") else listOf("--serve", "--port", port)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.apum.MainKt"
    }
}
