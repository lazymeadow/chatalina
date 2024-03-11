package com.applepeacock.database

import io.ktor.server.application.*
import kotlinx.datetime.Clock
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.io.File

fun Application.configureDatabases() {
    val url = environment.config.property("db.url").getString()
    val user = environment.config.property("db.user").getString()
    val password = environment.config.property("db.password").getString()

    val coreSchema = Schema("core")

    Database.connect(
        url = url,
        user = user,
        driver = "org.postgresql.Driver",
        password = password,
        databaseConfig = DatabaseConfig.invoke {
            defaultSchema = coreSchema
        }
    )

    transaction {
        val flyway = Flyway.configure().dataSource(url, user, password)
            .locations(Location("filesystem:db/migrations"))
            .load()
        flyway.migrate()

        SchemaUtils.setSchema(coreSchema)
        // check for any changes to schema
        val exposedTables = ChatTable::class.sealedSubclasses.mapNotNull { sealedTableClass -> sealedTableClass.objectInstance?.let { it as Table } }
        exposedTables.forEach {
            println(it.tableName)
        }
        val migrationStatements =
            SchemaUtils.statementsRequiredToActualizeScheme(*exposedTables.toTypedArray(), withLogs = true) +
                    SchemaUtils.columnDropStatements(Parasites)

        if (migrationStatements.isNotEmpty()) {
            val newMigrationFile = File("db/migrations/V${Clock.System.now().epochSeconds}__generated_migration.sql")
            newMigrationFile.createNewFile()
            if (!coreSchema.exists()) {
                coreSchema.createStatement().forEach { newMigrationFile.appendText(it + ";\n") }
            }
            coreSchema.setSchemaStatement().forEach { newMigrationFile.appendText(it + ";\n") }
            migrationStatements.forEach { newMigrationFile.appendText(it + ";\n") }
            // now run the migration again for the new file
            flyway.migrate()
        }
    }
}

fun SchemaUtils.columnDropStatements(vararg tables: Table): List<String> {
    val existingTablesColumns = currentDialect.tableColumns(*tables)
    val statements = ArrayList<String>()
    for (table in tables) {
        val thisTableExistingColumns = existingTablesColumns[table].orEmpty()
        // find the columns in thisTableExistingColumns that aren't in table.columns
        val excessiveColumns = thisTableExistingColumns.filter { existingColumn ->
            table.columns.find { it.name.equals(existingColumn.name, true) } == null
        }
        excessiveColumns.forEach { statements.add("ALTER TABLE ${table.nameInDatabaseCase()} DROP COLUMN ${it.name}") }
    }
    return statements
}
