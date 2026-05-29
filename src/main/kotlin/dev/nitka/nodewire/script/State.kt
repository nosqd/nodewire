package dev.nitka.nodewire.script

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A persisted state slot. The delegated property's **name** becomes the NBT
 * key (mirroring how stock tick nodes persist via getInt/putInt). [kind]
 * selects which NBT accessor the host uses when loading/saving.
 */
class StateCell<T>(val key: String, var value: T, val kind: StateKind)

/** The NBT-persistable allowlist for `state(...)` cells. */
enum class StateKind { INT, FLOAT, BOOL, STRING, REDSTONE }

/**
 * `var t by state(0)` — declares a persisted state cell. The initial value's
 * runtime type picks the [StateKind]; an unsupported type is a declaration
 * error surfaced on the node.
 *
 * The `ScriptModule` is supplied as the **extension receiver** (it's an
 * implicit receiver inside a compiled script body), so the cell registers into
 * [ScriptModule.stateCells]. The delegate-property receiver (`thisRef`),
 * however, is `Any?`: a script *top-level* `var x by …` has no dispatch
 * receiver (`thisRef == null`), so typing the provider/property to `ScriptModule`
 * would make Kotlin reject the delegate (`setValue(Nothing?, …)` not found).
 */
fun <T> ScriptModule.state(init: T): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> {
    val kind = when (init) {
        is Int -> StateKind.INT
        is Float -> StateKind.FLOAT
        is Boolean -> StateKind.BOOL
        is String -> StateKind.STRING
        is Redstone -> StateKind.REDSTONE
        else -> throw ScriptDeclException(
            "state var must be Int/Float/Boolean/String/Redstone (got ${init?.let { it::class.simpleName } ?: "null"})",
        )
    }
    val cells = stateCells
    return PropertyDelegateProvider { _, prop ->
        // Annotate the type arg explicitly: the `is Int`/`is Float`/… checks
        // smart-cast `init` to `T & Any`, which would otherwise infer
        // StateCell<T & Any> and break the nullable-capable T contract below.
        val cell = StateCell<T>(prop.name, init, kind).also { cells += it }
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = cell.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { cell.value = value }
        }
    }
}
