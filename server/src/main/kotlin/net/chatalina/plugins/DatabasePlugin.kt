package net.chatalina.plugins

import io.ktor.server.application.*
import io.ktor.util.*
import net.chatalina.database.Messages
import net.chatalina.database.Settings
import net.chatalina.database.Parasites
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource


class DatabasePlugin(configuration: PluginConfiguration) {
    private var dataSource: DataSource = PGSimpleDataSource().apply {
        setURL(configuration.url)
        databaseName = configuration.database
        user = configuration.user
        password = configuration.password
    }
    private val exposedDb by lazy {
        Database.connect(dataSource)
    }

    class PluginConfiguration {
        lateinit var url: String
        lateinit var database: String
        lateinit var user: String
        lateinit var password: String
    }

    init {
        TransactionManager.defaultDatabase = exposedDb

        val tables = listOf(Parasites, Settings, Messages).toTypedArray()

        transaction {
            SchemaUtils.createSchema(Schema("chatalina"))
            // first, execute to create and add stuff
            SchemaUtils.createMissingTablesAndColumns(*tables, inBatch = true, withLogs = true)
            // second, we want to do removal. exposed doesn't do it for us, but i wants it.
            // drop tables that are in the database, but aren't in the list of tables
            val realTables = currentDialect.allTablesNames()
            val wantedTables = tables.map { it.nameInDatabaseCase() }
            val dropStmts = mutableListOf<String>()
            realTables.filterNot { wantedTables.contains(it.substringAfter(".")) }.mapTo(dropStmts) {
                "DROP TABLE ${it};"
            }
            // drop removed columns from tables that are sticking around
            val realColumns = currentDialect.tableColumns(*tables)
            realColumns.mapNotNullTo(dropStmts) { (table, cols) ->
                val deadCols = cols.filterNot { col -> table.columns.any { it.name.equals(col.name, true) } }
                if (deadCols.isNotEmpty()) {
                    val dropClauses = deadCols.map { "DROP COLUMN ${it.name}" }
                    "ALTER TABLE ${table.nameInDatabaseCase()} ${dropClauses.joinToString(", ")};"
                } else null
            }
            // now execute all the drops
            if (dropStmts.isNotEmpty()) {
                this.execInBatch(dropStmts)
            }
        }
    }

    companion object Feature : BaseApplicationPlugin<ApplicationCallPipeline, PluginConfiguration, DatabasePlugin> {
        override val key = AttributeKey<DatabasePlugin>("flyway")

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: PluginConfiguration.() -> Unit
        ): DatabasePlugin {
            val configuration = PluginConfiguration().apply(configure)
            return DatabasePlugin(configuration)
        }
    }
}

fun Application.configureDatabase() {
    install(DatabasePlugin) {
        url = environment.config.property("db.url").getString()
        database = environment.config.property("db.database").getString()
        user = environment.config.property("db.user").getString()
        password = environment.config.property("db.password").getString()
    }
}
