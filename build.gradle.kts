val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val exposed_version: String by project
val h2_version: String by project
val postgres_version: String by project
val bcrypt_version: String by project

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
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-sessions")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("io.ktor:ktor-server-pebble")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-netty")

    implementation("at.favre.lib:bcrypt:$bcrypt_version")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("org.flywaydb:flyway-core:9.21.1")

    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-sessions-jvm")

    implementation("aws.sdk.kotlin:s3:1.0.40")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp-jvm")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")

    testImplementation("io.ktor:ktor-server-tests")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}