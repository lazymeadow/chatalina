import com.github.gradle.node.npm.task.NpmTask

val kotlinVersion: String = "1.9.22"
val logbackVersion: String = "1.5.0"
val exposedVersion: String = "0.47.0"
val postgresVersion: String = "42.7.2"
val bcryptVersion: String = "0.10.2"
val flywayVersion: String = "10.8.1"
val apacheCommonsEmailVersion: String = "1.6.0"
val apacheCommonsValidatorVersion: String = "1.8.0"
val autolinkVersion = "0.11.0"
val awsVersion: String = "1.0.40"

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.8"
    id("org.flywaydb.flyway") version "10.8.1"
    id("com.github.node-gradle.node") version "7.0.2"
}

kotlin {
    jvmToolchain(20)
}

flyway {
    locations = arrayOf("classpath:db/migrations")
}

group = "com.applepeacock"
version = "0.0.1"
application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

node {
    nodeProjectDir.set(file("web-client"))
}

val buildNpmTask = tasks.register<NpmTask>("buildNpm") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "build"))
    environment.set(mapOf("BEC_SERVER" to "localhost:6969"))
    outputs.upToDateWhen {
        false
    }
}

tasks.register<Copy>("moveFrontend") {
    dependsOn(buildNpmTask)
    from(node.nodeProjectDir.dir("dist"))
    into(layout.projectDirectory.dir("src/main/resources/static"))
}

tasks.processResources.get().dependsOn(tasks.named("moveFrontend").get())


repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-pebble")

    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-forwarded-header")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-sessions")
    implementation("io.ktor:ktor-server-sessions-jvm")
    implementation("at.favre.lib:bcrypt:$bcryptVersion")

    implementation("io.ktor:ktor-server-content-negotiation")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp-jvm")
    implementation("io.ktor:ktor-client-content-negotiation")

    implementation("io.ktor:ktor-serialization-jackson")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("org.apache.commons:commons-email:$apacheCommonsEmailVersion")
    implementation("commons-validator:commons-validator:$apacheCommonsValidatorVersion")

    implementation("org.nibor.autolink:autolink:$autolinkVersion")

    implementation("aws.sdk.kotlin:s3:$awsVersion")

    testImplementation("io.ktor:ktor-server-tests")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}