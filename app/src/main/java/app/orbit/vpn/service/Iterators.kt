package app.orbit.vpn.service

import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/** Adapters between Kotlin collections and gomobile iterator interfaces. */
internal class StringArray(private val values: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = values.size
    override fun hasNext(): Boolean = index < values.size
    override fun next(): String = values[index++]
}

internal class InterfaceArray(private val iterator: Iterator<BoxNetworkInterface>) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): BoxNetworkInterface = iterator.next()
}

internal fun StringIterator.toList(): List<String> = buildList { while (hasNext()) add(next()) }

internal fun StringIterator.forEach(action: (String) -> Unit) {
    while (hasNext()) action(next())
}

internal fun RoutePrefixIterator.toList(): List<RoutePrefix> = buildList { while (hasNext()) add(next()) }

internal fun RoutePrefixIterator.forEach(action: (RoutePrefix) -> Unit) {
    while (hasNext()) action(next())
}
