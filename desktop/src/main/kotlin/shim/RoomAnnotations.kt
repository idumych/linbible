/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Room's annotations, kept at runtime so that MiniRoom (org.linbible.MiniRoom) can implement AndBible's entities and
 * DAO interfaces, which LinBible compiles unmodified.
 */
@file:Suppress("unused")

package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [],
)

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class ColumnInfo(
    val name: String = INHERIT_FIELD_NAME,
    val typeAffinity: Int = UNDEFINED,
    val index: Boolean = false,
    val collate: Int = UNSPECIFIED,
    val defaultValue: String = VALUE_UNSPECIFIED,
) {
    companion object {
        const val INHERIT_FIELD_NAME = "[field-name]"
        const val UNDEFINED = 1
        const val UNSPECIFIED = 1
        const val VALUE_UNSPECIFIED = "[value-unspecified]"
    }
}

@Retention(AnnotationRetention.RUNTIME)
annotation class Index(vararg val value: String, val orders: Array<String> = [], val name: String = "", val unique: Boolean = false)

@Retention(AnnotationRetention.RUNTIME)
annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false,
) {
    companion object {
        const val NO_ACTION = 1
        const val RESTRICT = 2
        const val SET_NULL = 3
        const val SET_DEFAULT = 4
        const val CASCADE = 5
    }
}

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY) @Retention(AnnotationRetention.RUNTIME)
annotation class Ignore

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class DatabaseView(val value: String = "", val viewName: String = "")

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Dao

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Update(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Delete(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Transaction

@Retention(AnnotationRetention.RUNTIME)
annotation class TypeConverters(vararg val value: KClass<*>)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class TypeConverter

@Retention(AnnotationRetention.RUNTIME)
annotation class OnConflictStrategy {
    companion object {
        const val NONE = 0
        const val REPLACE = 1
        const val ROLLBACK = 2
        const val ABORT = 3
        const val FAIL = 4
        const val IGNORE = 5
    }
}

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class Embedded(val prefix: String = "")

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Upsert(val entity: KClass<*> = Any::class)
