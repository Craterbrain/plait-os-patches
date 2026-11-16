package app.plait.broker

import org.junit.Assert.assertEquals
import org.junit.Test

class DeclaredTest {
    private val a = Declared("exec", "ip neigh show", "neighbors")
    private val b = Declared("exec", "cmd wifi list-scan-results", "wifi")
    private val c = Declared("ask", "connections", "connections")

    @Test fun onlyUnheardOfItemsAreNew() {
        val known = listOf(Cap("exec", "ip neigh show", true, 3, 0, 0))
        assertEquals(listOf(b, c), newDeclarations(listOf(a, b, c), known))
    }

    @Test fun anItemTheUserSwitchedOffIsKnownNotNew() {
        val known = listOf(Cap("exec", "ip neigh show", true, 1, 0, 0), Cap("exec", "cmd wifi list-scan-results", false, 0, 1, 0))
        assertEquals(listOf(c), newDeclarations(listOf(a, b, c), known))
    }

    @Test fun sameKeyOfAnotherTypeIsStillNew() {
        assertEquals(listOf(c), newDeclarations(listOf(c), listOf(Cap("exec", "connections", true, 0, 0, 0))))
    }

    @Test fun nothingNewWhenEverythingIsKnown() {
        assertEquals(emptyList<Declared>(), newDeclarations(listOf(a), listOf(Cap("exec", "ip neigh show", false, 0, 0, 0))))
    }
}
