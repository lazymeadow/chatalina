package com.applepeacock.database

import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction


lateinit var emojiDbConnection: Database

fun Application.configureDatabases() {
    val url = environment.config.property("db.url").getString()
    val user = environment.config.property("db.user").getString()
    val password = environment.config.property("db.password").getString()

    val coreSchema = Schema("core")

    val chatDbConnection = Database.connect(
        url = url,
        user = user,
        driver = "org.postgresql.Driver",
        password = password,
        databaseConfig = DatabaseConfig.invoke {
            defaultSchema = coreSchema
        }
    )

    TransactionManager.defaultDatabase = chatDbConnection

    emojiDbConnection = Database.connect(
        url = url,
        user = user,
        driver = "org.postgresql.Driver",
        password = password,
        databaseConfig = DatabaseConfig.invoke {
            defaultSchema = Schema("emoji")
        }
    )

    transaction {
        val flyway = Flyway.configure().dataSource(url, user, password)
            .locations(Location("classpath:db/migrations"))
            .load()
        flyway.migrate()
    }
}
