package com.applepeacock.database

import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject


fun Table.systemTimestamp(name: String) = timestamp(name).clientDefault { Clock.System.now() }

sealed interface ChatTable {
    abstract class ObjectModel
    abstract class DAO {
        abstract fun resultRowToObject(row: ResultRow): ObjectModel
    }
}

// json column type (see: https://gist.github.com/qoomon/70bbbedc134fd2a149f1f2450667dc9d, https://gist.github.com/nvta-sbiyyala/fa327d449d98f37dacead7241af2c683)

val databaseMapper = defaultMapper

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> =
    jsonb(name = name, klass = T::class.java)

fun <T : Any> Table.jsonb(name: String, klass: Class<T>): Column<T> =
    registerColumn(name = name, type = PostgresJsonBColumn(klass, false))

private class PostgresJsonBColumn<out T : Any>(
    private val klass: Class<T>,
    override var nullable: Boolean
) : IColumnType {
    override fun sqlType(): String = "JSONB"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = sqlType()
        if (value != null)
            obj.value = value as String
        stmt[index] = obj
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is HashMap<*, *> -> value
        is Map<*, *> -> value
        else -> {
            if (value::class.java == klass) {
                value
            } else {
                value as PGobject
                try {
                    val json = value.value
                    databaseMapper.readValue(json, klass)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw RuntimeException("Can't parse JSON: $value")
                }
            }
        }
    }

    override fun nonNullValueToString(value: Any): String = "'${databaseMapper.writeValueAsString(value)}'::jsonb"

    override fun notNullValueToDB(value: Any): Any = databaseMapper.writeValueAsString(value)
}

// jsonb_set function
// jsonb_set(metadata, '{key}', '"new value"')
class JsonbSet<T : Any>(val column: Expression<T>, klass: Class<T>, val key: String, val value: Any?) :
    CustomFunction<T>(
        "JSONB_SET",
        PostgresJsonBColumn(klass, false)
    ) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(functionName, '(')
        append(column)
        append(", ")
        append(stringLiteral("{${key}}"))
        append(", ")
        if (value is Expression<*>) {
            value.toQueryBuilder(this)
        } else {
            append(value?.let { "${stringParam("\"$it\"")}" } ?: stringLiteral("null"))
        }
        append(')')
    }
}

fun <T : Any> jsonbSet(col: Column<T>, klass: Class<T>, key: String, value: String?): JsonbSet<T> =
    JsonbSet(col, klass, key, value)


fun <T : Any> jsonbSetNullable(
    col: Expression<T?>,
    valueIfNull: T,
    klass: Class<T>,
    key: String,
    value: Any?
): JsonbSet<T> =
    JsonbSet<T>(Expression.build {
        case().When(col.isNotNull(), col).Else(jsonbParam<T>(valueIfNull, klass))
    }, klass, key, value)

fun <T : Any> jsonbParam(value: T, klass: Class<T>) =
    QueryParameter(value, PostgresJsonBColumn(klass, false))

// json_agg function
fun <T : Any> jsonAgg(expr: QueryAlias, klass: Class<T>): ExpressionWithColumnType<T> = CustomFunction(
    "JSON_AGG",
    PostgresJsonBColumn(klass, false),
    wrapAsExpression<T>(expr.query).alias(expr.alias).aliasOnlyExpression()
)

// jsonb_agg function
class JsonbAgg<T : Any>(val expr1: Expression<*>, klass: Class<T>, val filter: Op<Boolean>? = null) : CustomFunction<T>(
    "JSONB_AGG",
    PostgresJsonBColumn(klass, false)
) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(functionName, "(", expr1, ")")
        filter?.let {
            append(" FILTER (WHERE ", filter, ")")
        }
    }
}

fun <T : Any> jsonbAgg(expr: Expression<*>, klass: Class<T>, filter: Op<Boolean>? = null) =
    JsonbAgg(expr, klass, filter)

fun jsonbTextAgg(col: Column<String>): ExpressionWithColumnType<Array<String>> = CustomFunction(
    "JSONB_AGG",
    PostgresJsonBColumn(Array<String>::class.java, false),
    col
)

fun jsonbArrayElementsText(expr: ExpressionWithColumnType<*>): CustomFunction<String?> = CustomStringFunction(
    "JSONB_ARRAY_ELEMENTS_TEXT",
    expr
)

open class JsonArrowOp<T>(val expr1: Expression<*>, val expr2: Expression<*>) : ExpressionWithColumnType<Any?>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(expr1, "->", expr2)
    }

    infix fun eq(other: String): EqOp = EqOp(this, stringParam(other))
    override val columnType: IColumnType = TextColumnType()
}

class JsonDoubleArrowOp<T>(expr1: Expression<*>, expr2: Expression<*>) : JsonArrowOp<T>(expr1, expr2) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(expr1, "->>", expr2)
    }
}

class JsonContainsArrowOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")

// -> operator for json nodes by key
infix fun <T> Column<T>.singleArrow(string: String): JsonArrowOp<T> = JsonArrowOp(this, stringLiteral(string))

// ->> operator for json nodes as text
infix fun <T> Column<T>.doubleArrow(string: String): JsonDoubleArrowOp<T> =
    JsonDoubleArrowOp(this, stringLiteral(string))

infix fun <T> JsonArrowOp<T>.doubleArrow(string: String): JsonDoubleArrowOp<T> =
    JsonDoubleArrowOp(this, stringLiteral(string))

infix fun <T> JsonArrowOp<T>.containsArrow(expr: ExpressionAlias<Array<String>>): JsonContainsArrowOp =
    JsonContainsArrowOp(this, expr.aliasOnlyExpression())