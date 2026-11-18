package app.hyperlpa.ui

import app.hyperlpa.ui.navigation.RouteTransitionGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTransitionGuardTest {
    @Test fun openingAcceptsOnlyTheNewOwnerAndKeepsStackKeysUnique() {
        val stack = mutableListOf("root")
        val guard = RouteTransitionGuard(stack)
        assertTrue(guard.open("root", "parent"))
        stack.add("parent")
        assertFalse(guard.open("root", "sibling"))
        assertFalse(guard.open("parent", "parent"))
        assertFalse(guard.open("parent", "root"))
        assertTrue(guard.open("parent", "child"))
    }

    @Test fun successiveBackActionsCanPopDifferentOwnersWithoutSettling() {
        val stack = mutableListOf("root", "parent", "child")
        val guard = RouteTransitionGuard(stack)
        assertTrue(guard.back("child"))
        stack.removeAt(stack.lastIndex)
        assertFalse(guard.back("child"))
        assertTrue(guard.back("parent"))
        assertTrue(guard.open("parent", "child"))
        stack.removeAt(stack.lastIndex)
        assertFalse(guard.back("root"))
    }
}
