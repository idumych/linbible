/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * A small runtime replacement for Room's generated code. AndBible's entity classes and DAO interfaces (AI settings,
 * AI documents) are compiled unmodified; MiniRoom implements the DAO interfaces with dynamic proxies that run the SQL
 * of their @Query annotations and map rows to the entity classes by constructor parameter names. Values are stored
 * like AndBible's Room TypeConverters do (ids as 16 byte blobs, enums by name, collections as JSON), so the
 * databases stay interchangeable with AndBible.
 */
package org.linbible

import androidx.room.ColumnInfo
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import net.bible.android.database.IdType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.util.Date
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction

object MiniRoom {
    /** AndBible's Converters use this Json configuration (WorkspaceEntities.json). */
    private val convJson = Json { allowStructuredMapKeys = true; encodeDefaults = true; ignoreUnknownKeys = true }

    inline fun <reified T : Any> dao(noinline db: () -> RoomDb): T = create(T::class.java, db)

    @Suppress("UNCHECKED_CAST")
    fun <T> create(iface: Class<T>, db: () -> RoomDb): T =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, rawArgs ->
            val args = rawArgs?.filter { it !is Continuation<*> }?.toTypedArray() ?: emptyArray()
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "toString" -> "MiniRoom(${iface.simpleName})"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === rawArgs?.get(0)
                    else -> null
                }
                method.isDefault -> {
                    val call = { InvocationHandler.invokeDefault(proxy, method, *(rawArgs ?: emptyArray())) }
                    if (method.isAnnotationPresent(Transaction::class.java)) db().tx(call) else call()
                }
                method.isAnnotationPresent(Query::class.java) -> query(db(), method, args)
                method.isAnnotationPresent(Insert::class.java) -> insert(db(), method, args)
                method.isAnnotationPresent(androidx.room.Upsert::class.java) -> upsert(db(), method, args)
                method.isAnnotationPresent(Update::class.java) -> update(db(), method, args)
                method.isAnnotationPresent(Delete::class.java) -> delete(db(), method, args)
                else -> throw UnsupportedOperationException("MiniRoom: ${iface.simpleName}.${method.name}")
            }
        } as T

    // ---------------------------------------------------------------- value conversion (Room TypeConverters)

    private fun KType.kclass(): KClass<*>? = classifier as? KClass<*>

    fun toDb(value: Any?, type: KType?): Any? = when (value) {
        null -> null
        is IdType -> value.toByteArray()
        // AndBible Converters: versification by name, books by initials, verse ranges as "v11n::osisRef",
        // keys as base64 of the serialized Java object
        is org.crosswire.jsword.versification.Versification -> value.name
        is org.crosswire.jsword.book.Book -> value.initials
        is org.crosswire.jsword.passage.VerseRange -> "${value.versification.name}::${value.osisRef}"
        is org.crosswire.jsword.passage.Key -> java.io.ByteArrayOutputStream().also { java.io.ObjectOutputStream(it).use { o -> o.writeObject(value) } }
            .toByteArray().let { java.util.Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(it) + "\n" }
        is Boolean -> if (value) 1 else 0
        is Enum<*> -> value.name
        is Date -> value.time
        is String, is Number, is ByteArray -> value
        else -> {
            val t = type ?: error("MiniRoom: no type for ${value::class}")
            convJson.encodeToString(serializer(t), value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun fromDb(value: Any?, type: KType): Any? {
        val k = type.kclass() ?: return value
        if (value == null) {
            if (type.isMarkedNullable) return null
            return when {
                k.isSubclassOf(Set::class) -> mutableSetOf<Any>()
                k.isSubclassOf(List::class) -> mutableListOf<Any>()
                k.isSubclassOf(Map::class) -> mutableMapOf<Any, Any>()
                else -> null
            }
        }
        return when {
            k == IdType::class -> IdType.fromByteArray(value as? ByteArray ?: (value as? String)?.toByteArray())
            k == String::class -> value.toString()
            k == Int::class -> (value as? Number)?.toInt() ?: value.toString().toInt()
            k == Long::class -> (value as? Number)?.toLong() ?: value.toString().toLong()
            k == Double::class -> (value as? Number)?.toDouble() ?: value.toString().toDouble()
            k == Float::class -> (value as? Number)?.toFloat() ?: value.toString().toFloat()
            k == Short::class -> (value as Number).toShort()
            k == Boolean::class -> ((value as? Number)?.toInt() ?: value.toString().toInt()) != 0
            k == ByteArray::class -> value as? ByteArray ?: value.toString().toByteArray()
            k == Date::class -> Date((value as Number).toLong())
            k.isSubclassOf(org.crosswire.jsword.versification.Versification::class) ->
                org.crosswire.jsword.versification.system.Versifications.instance().getVersification(value.toString())
            k == org.crosswire.jsword.passage.VerseRange::class -> value.toString().split("::").let { parts ->
                runCatching { org.crosswire.jsword.passage.VerseRangeFactory.fromString(
                    org.crosswire.jsword.versification.system.Versifications.instance().getVersification(parts[0]), parts[1]) }.getOrNull()
            }
            k.isSubclassOf(org.crosswire.jsword.book.Book::class) ->
                org.crosswire.jsword.book.Books.installed().getBook(value.toString())?.takeIf { k.isInstance(it) }
            k.isSubclassOf(org.crosswire.jsword.passage.Key::class) -> runCatching {
                java.io.ObjectInputStream(java.io.ByteArrayInputStream(java.util.Base64.getMimeDecoder().decode(value.toString()))).use { it.readObject() }
            }.getOrNull()
            k.java.isEnum -> runCatching { java.lang.Enum.valueOf(k.java as Class<out Enum<*>>, value.toString()) }.getOrNull()
            else -> runCatching { convJson.decodeFromString(serializer(type), value.toString()) }.getOrElse {
                Log.e("MiniRoom: could not decode $value as $type", it)
                fromDb(null, type)
            }
        }
    }

    private fun isScalar(k: KClass<*>) = k == IdType::class || k == String::class || k == Int::class || k == Long::class ||
        k == Double::class || k == Float::class || k == Boolean::class || k == ByteArray::class || k == Short::class || k.java.isEnum

    // ---------------------------------------------------------------- entity metadata

    /** A column; for @Embedded objects [get] reads the nested property (columns are prefixed). */
    class Column(val name: String, val param: KParameter?, val type: KType, val get: (Any) -> Any?, val isPk: Boolean, val autoGenerate: Boolean)

    class EntityInfo(val kclass: KClass<*>, val table: String, val columns: List<Column>) {
        val pk get() = columns.filter { it.isPk }
    }

    private val entityCache = HashMap<KClass<*>, EntityInfo>()

    @Suppress("UNCHECKED_CAST")
    fun entityInfo(k: KClass<*>): EntityInfo = synchronized(entityCache) {
        entityCache.getOrPut(k) {
            val ann = k.findAnnotation<Entity>()
            val table = ann?.tableName?.takeIf { it.isNotEmpty() } ?: k.simpleName!!
            val ctor = k.primaryConstructor ?: error("MiniRoom: ${k.simpleName} has no primary constructor")
            val props = k.memberProperties.associateBy { it.name }
            val pkNames = ann?.primaryKeys?.toSet() ?: emptySet()
            val cols = ctor.parameters.filter { it.findAnnotation<Ignore>() == null }.flatMap { p ->
                val prop = props[p.name] as KProperty1<Any, *>
                val embedded = p.findAnnotation<androidx.room.Embedded>()
                if (embedded != null) {
                    val sub = (p.type.classifier as KClass<*>)
                    val subProps = sub.memberProperties.associateBy { it.name }
                    sub.primaryConstructor!!.parameters.map { sp ->
                        val subProp = subProps[sp.name] as KProperty1<Any, *>
                        Column(embedded.prefix + columnName(sp), null, sp.type, { e -> prop.get(e)?.let { subProp.get(it) } }, false, false)
                    }
                } else {
                    val pk = p.findAnnotation<PrimaryKey>()
                    val name = columnName(p)
                    listOf(Column(name, p, p.type, { e -> prop.get(e) }, pk != null || name in pkNames, pk?.autoGenerate == true))
                }
            }
            EntityInfo(k, table, cols)
        }
    }

    private fun columnName(p: KParameter): String =
        p.findAnnotation<ColumnInfo>()?.name?.takeIf { it != ColumnInfo.INHERIT_FIELD_NAME } ?: p.name!!

    private fun readEntity(rs: ResultSet, k: KClass<*>, prefix: String = ""): Any? {
        val md = rs.metaData
        val available = (1..md.columnCount).associateBy { md.getColumnLabel(it) }
        val ctor = k.primaryConstructor!!
        val args = HashMap<KParameter, Any?>()
        var anyValue = false
        for (p in ctor.parameters) {
            if (p.findAnnotation<Ignore>() != null) continue
            val embedded = p.findAnnotation<androidx.room.Embedded>()
            if (embedded != null) {
                args[p] = readEntity(rs, p.type.classifier as KClass<*>, prefix + embedded.prefix)
                continue
            }
            val idx = available[prefix + columnName(p)]
            if (idx == null) {
                if (!p.isOptional && p.type.isMarkedNullable) args[p] = null
                continue
            }
            val raw = rs.getObject(idx)
            if (raw != null) anyValue = true
            val v = fromDb(raw, p.type)
            if (v == null && !p.type.isMarkedNullable) { if (!p.isOptional) error("MiniRoom: NULL for ${k.simpleName}.${p.name}") }
            else args[p] = v
        }
        // an embedded object whose columns are all NULL is null (Room semantics)
        if (prefix.isNotEmpty() && !anyValue) return null
        return ctor.callBy(args)
    }

    // ---------------------------------------------------------------- @Query

    private val paramRe = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")

    private fun query(db: RoomDb, method: Method, args: Array<Any?>): Any? {
        val kf = method.kotlinFunction ?: error("MiniRoom: no Kotlin metadata for ${method.name}")
        val params = kf.valueParameters
        val byName = params.mapIndexed { i, p -> p.name to (args.getOrNull(i) to p.type) }.toMap()
        val bind = mutableListOf<Any?>()
        val sql = paramRe.replace(method.getAnnotation(Query::class.java).value) { m ->
            val (v, t) = byName[m.groupValues[1]] ?: error("MiniRoom: unknown parameter :${m.groupValues[1]} in ${method.name}")
            if (v is Collection<*>) {
                val elemType = t.arguments.firstOrNull()?.type
                v.forEach { bind += toDb(it, elemType) }
                v.joinToString(",") { "?" }.ifEmpty { "NULL" }
            } else { bind += toDb(v, t); "?" }
        }
        val isSelect = sql.trimStart().let { it.startsWith("SELECT", true) || it.startsWith("WITH", true) }
        val ret = kf.returnType
        if (!isSelect) {
            val n = db.update(sql, *bind.toTypedArray())
            return if (ret.kclass() == Int::class) n else Unit
        }
        val rk = ret.kclass()!!
        val isCollection = rk.isSubclassOf(List::class) || rk.isSubclassOf(Set::class) || rk.isSubclassOf(Collection::class)
        val elemType = if (isCollection) ret.arguments.first().type!! else ret
        val elemClass = elemType.kclass()!!
        val rows = db.query(sql, *bind.toTypedArray()) { rs ->
            if (isScalar(elemClass)) fromDb(rs.getObject(1), elemType) else readEntity(rs, elemClass)!!
        }
        return when {
            rk.isSubclassOf(Set::class) -> rows.toMutableSet()
            isCollection -> rows
            else -> rows.firstOrNull() ?: if (ret.isMarkedNullable) null else when (rk) {
                Int::class -> 0; Long::class -> 0L; Boolean::class -> false; Double::class -> 0.0
                else -> error("MiniRoom: no result for ${method.name}")
            }
        }
    }

    // ---------------------------------------------------------------- @Insert / @Update / @Delete

    private fun entities(args: Array<Any?>): List<Any> = args.flatMap { a ->
        when (a) {
            null -> emptyList()
            is Array<*> -> a.filterNotNull()
            is Collection<*> -> a.filterNotNull()
            else -> listOf(a)
        }
    }

    private fun insert(db: RoomDb, method: Method, args: Array<Any?>): Any? {
        val conflict = method.getAnnotation(Insert::class.java).onConflict
        val verb = when (conflict) {
            OnConflictStrategy.REPLACE -> "INSERT OR REPLACE"
            OnConflictStrategy.IGNORE -> "INSERT OR IGNORE"
            else -> "INSERT"
        }
        val ids = db.tx {
            entities(args).map { e ->
                val info = entityInfo(e::class)
                val cols = info.columns.filterNot { c -> c.autoGenerate && (c.get(e) as? Number)?.toLong() == 0L }
                db.update("$verb INTO `${info.table}` (${cols.joinToString(",") { "`${it.name}`" }}) VALUES (${cols.joinToString(",") { "?" }})",
                    *cols.map { toDb(it.get(e), it.type) }.toTypedArray())
                db.query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
            }
        }
        return result(method, ids)
    }

    private fun upsert(db: RoomDb, method: Method, args: Array<Any?>): Any? {
        val ids = db.tx {
            entities(args).map { e ->
                val info = entityInfo(e::class)
                val cols = info.columns
                val set = cols.filterNot { it.isPk }.joinToString(",") { "`${it.name}`=excluded.`${it.name}`" }
                db.update("INSERT INTO `${info.table}` (${cols.joinToString(",") { "`${it.name}`" }}) VALUES (${cols.joinToString(",") { "?" }}) " +
                    "ON CONFLICT(${info.pk.joinToString(",") { "`${it.name}`" }}) DO " + if (set.isEmpty()) "NOTHING" else "UPDATE SET $set",
                    *cols.map { toDb(it.get(e), it.type) }.toTypedArray())
                db.query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
            }
        }
        return result(method, ids)
    }

    private fun update(db: RoomDb, method: Method, args: Array<Any?>): Any? {
        val counts = db.tx {
            entities(args).map { e ->
                val info = entityInfo(e::class)
                val set = info.columns.filterNot { it.isPk }
                db.update("UPDATE `${info.table}` SET ${set.joinToString(",") { "`${it.name}` = ?" }} WHERE ${info.pk.joinToString(" AND ") { "`${it.name}` = ?" }}",
                    *(set + info.pk).map { toDb(it.get(e), it.type) }.toTypedArray())
            }
        }
        return if (method.returnType == Int::class.javaPrimitiveType) counts.sum() else Unit
    }

    private fun delete(db: RoomDb, method: Method, args: Array<Any?>): Any? {
        val counts = db.tx {
            entities(args).map { e ->
                val info = entityInfo(e::class)
                db.update("DELETE FROM `${info.table}` WHERE ${info.pk.joinToString(" AND ") { "`${it.name}` = ?" }}",
                    *info.pk.map { toDb(it.get(e), it.type) }.toTypedArray())
            }
        }
        return if (method.returnType == Int::class.javaPrimitiveType) counts.sum() else Unit
    }

    private fun result(method: Method, ids: List<Long>): Any? {
        val kf = method.kotlinFunction
        val rk = kf?.returnType?.kclass()
        return when {
            rk == null || rk == Unit::class -> Unit
            rk == Long::class -> ids.firstOrNull() ?: -1L
            rk == LongArray::class -> ids.toLongArray()
            rk.isSubclassOf(List::class) -> ids
            rk == Array<Long>::class -> ids.toTypedArray()
            else -> Unit
        }
    }
}
