package net.chatalina.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertSelectStatement
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*

// from https://github.com/LorittaBot/Loritta

open class ArrayColumnType<T>(private val type: ColumnType) : ColumnType() {
    override fun sqlType(): String = "${type.sqlType()} ARRAY"

    override fun valueToDB(value: Any?): Any? {
        if (value is Array<*>) {
            val columnType = type.sqlType().split("(")[0]
            val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
            return jdbcConnection.createArrayOf(columnType, value)
        } else {
            return super.valueToDB(value)
        }
    }

    override fun valueFromDB(value: Any): Any {
        if (value is java.sql.Array) {
            return value.array
        }
        if (value is Array<*>) {
            return value
        }
        error("Unsupported array")
    }

    override fun notNullValueToDB(value: Any): Any {
        if (value is Array<*>) {
            if (value.isEmpty())
                return "'{}'"

            val columnType = type.sqlType().split("(")[0]
            val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
            return jdbcConnection.createArrayOf(columnType, value) ?: error("Can't create non null array for $value")
        } else {
            return super.notNullValueToDB(value)
        }
    }
}

class TextArrayColumnType() : ArrayColumnType<String>(TextColumnType())

fun Table.textArray(name: String): Column<Array<String>> = registerColumn(name, TextArrayColumnType())

class AnyOp(val expr1: Expression<*>, val expr2: Expression<*>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        if (expr2 is OrOp) {
            queryBuilder.append("(").append(expr2).append(")")
        } else {
            queryBuilder.append(expr2)
        }
        queryBuilder.append(" = ANY (")
        if (expr1 is OrOp) {
            queryBuilder.append("(").append(expr1).append(")")
        } else {
            queryBuilder.append(expr1)
        }
        queryBuilder.append(")")
    }
}

infix fun <T, S> ExpressionWithColumnType<T>.any(t: S): Op<Boolean> {
    if (t == null) {
        return IsNullOp(this)
    }
    return AnyOp(this, QueryParameter(t, columnType))
}

infix fun <T, S> Expression<T>.any(expr: Expression<S>): Op<Boolean> {
    return AnyOp(this, expr)
}

class ContainsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")

infix fun <T, S> ExpressionWithColumnType<T>.contains(array: Array<in S>): Op<Boolean> =
    ContainsOp(this, QueryParameter(array, columnType))

class OverlapsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "&&")

infix fun <T, S> ExpressionWithColumnType<T>.overlaps(array: Array<in S>): Op<Boolean> =
    OverlapsOp(this, QueryParameter(array, columnType))

class ArrayAggFunction<T : Any?>(expr: Expression<*>, _columnType: IColumnType) :
    CustomFunction<Array<T>>("array_agg", _columnType, expr)

fun Expression<Int>.intArrayAgg(): ArrayAggFunction<Int> =
    ArrayAggFunction(this, ArrayColumnType<Int>(IntegerColumnType()))

fun Expression<UUID>.uuidArrayAgg(): ArrayAggFunction<UUID> =
    ArrayAggFunction<UUID>(this, UUIDColumnType())

class ArrayLengthFunction(expr: Expression<*>) :
    CustomFunction<Int>("array_length", IntegerColumnType(), expr, intLiteral(1))

fun <T : Any?> Expression<T>.arrayLength(): ArrayLengthFunction = ArrayLengthFunction(this)


// Upsert operations, not supported by Exposed out of the box

/**
 * Example:
 * ```
 * val item = ...
 * MyTable.upsert {
 *  it[id] = item.id
 *  it[value1] = item.value1
 * }
 *```
 */
fun <T : Table> T.upsert(
    where: (SqlExpressionBuilder.() -> Op<Boolean>)? = null,
    vararg keys: Column<*> = (primaryKey ?: throw IllegalArgumentException("primary key is missing")).columns,
    body: T.(InsertStatement<Number>) -> Unit
) = InsertOrUpdate<Number>(this, keys = keys, where = where?.let { SqlExpressionBuilder.it() }).apply {
    body(this)
    execute(TransactionManager.current())
}

/**
 * Example:
 * ```
 * val item = ...
 * MyTable.upsert(
 *   OtherTable.slice(OtherTable.id, OtherTable.col1, intLiteral(someInt)).select { SomeCondition eq true },
 *   columns = listOf(MyTable.other_id, MyTable.other_value, MyTable.int_value)
 * )
 *```
 */
fun <T : Table> T.upsert(
    selectQuery: AbstractQuery<*>,
    columns: List<Column<*>> = this.columns.filter { !it.columnType.isAutoInc || it.autoIncColumnType?.nextValExpression != null },
    vararg keys: Column<*> = (primaryKey ?: throw IllegalArgumentException("primary key is missing")).columns
) = InsertSelectOrUpdate(
    this,
    keys = keys,
    selectQuery = selectQuery,
    columns = columns
).execute(TransactionManager.current())

class InsertOrUpdate<Key : Any>(
    table: Table,
    isIgnore: Boolean = false,
    private val where: Op<Boolean>? = null,
    private vararg val keys: Column<*>
) : InsertStatement<Key>(table, isIgnore) {
    override fun prepareSQL(transaction: Transaction): String {
        val onConflict = buildOnConflict(table, transaction, where, keys = keys)
        return "${super.prepareSQL(transaction)} $onConflict"
    }
}

class InsertSelectOrUpdate(
    val table: Table,
    selectQuery: AbstractQuery<*>,
    columns: List<Column<*>>,
    private vararg val keys: Column<*>,
    isIgnore: Boolean = false
) : InsertSelectStatement(columns, selectQuery, isIgnore) {
    override fun prepareSQL(transaction: Transaction): String {
        val onConflict = buildOnConflict(table, transaction, keys = keys)
        return "${super.prepareSQL(transaction)} $onConflict"
    }
}

fun buildOnConflict(
    table: Table,
    transaction: Transaction,
    where: Op<Boolean>? = null,
    vararg keys: Column<*>
): String {
    var updateSetter = (table.columns - keys).joinToString(", ") {
        "${transaction.identity(it)} = EXCLUDED.${transaction.identity(it)}"
    }
    where?.let {
        updateSetter += " WHERE $it"
    }
    return "ON CONFLICT (${keys.joinToString { transaction.identity(it) }}) DO UPDATE SET $updateSetter"
}
