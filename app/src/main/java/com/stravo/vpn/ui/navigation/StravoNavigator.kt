package com.stravo.vpn.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Простой стек навигации. Back: диалог → закрыть, вложенный экран → Home, Home → системный back.
 */
class StravoNavigator(initial: Destination = Destination.HOME) {

    private val stack: SnapshotStateList<Destination> = mutableStateListOf(initial)

    val current: Destination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** Переход по основным разделам: без разрастания стека. */
    fun select(destination: Destination) {
        if (destination == current) return
        stack.remove(destination)
        stack.add(destination)
    }

    /** Переход вглубь: экран кладётся поверх текущего. */
    fun push(destination: Destination) {
        if (destination == current) return
        stack.add(destination)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun routes(): List<String> = stack.map { it.route }

    companion object {
        fun saver(): Saver<StravoNavigator, List<String>> = Saver(
            save = { navigator -> navigator.routes() },
            restore = { routes ->
                val navigator = StravoNavigator(Destination.byRoute(routes.firstOrNull()))
                routes.drop(1).forEach { navigator.push(Destination.byRoute(it)) }
                navigator
            },
        )
    }
}
