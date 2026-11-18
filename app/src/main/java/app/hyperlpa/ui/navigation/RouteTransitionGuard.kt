package app.hyperlpa.ui.navigation

/** The logical stack changes at commit, independently of the visible animation. */
internal class RouteTransitionGuard<T>(private val stack: List<T>) {
    fun open(expected: T?, destination: T): Boolean =
        stack.lastOrNull() == expected && destination !in stack

    fun back(expected: T?): Boolean =
        stack.size > 1 && stack.last() == expected
}
