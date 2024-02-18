val kotlinVersion: String = "1.9.22"
val logbackVersion: String = "1.4.12"
val exposedVersion: String = "0.46.0"
val postgresVersion: String = "42.7.1"
val bcryptVersion: String = "0.10.2"
val flywayVersion: String = "9.21.1"
val apacheCommonsEmailVersion: String = "1.6.0"
val apacheCommonsValidatorVersion: String = "1.8.0"

plugins {
    kotlin("jvm") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("org.flywaydb.flyway") version "10.6.0"
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
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-sessions")
    implementation("io.ktor:ktor-server-sessions-jvm")
    implementation("at.favre.lib:bcrypt:$bcryptVersion")

    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")

    implementation("org.apache.commons:commons-email:$apacheCommonsEmailVersion")
    implementation("commons-validator:commons-validator:$apacheCommonsValidatorVersion")

    implementation("aws.sdk.kotlin:s3:1.0.40")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp-jvm")
    implementation("io.ktor:ktor-client-content-negotiation")

    testImplementation("io.ktor:ktor-server-tests")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}