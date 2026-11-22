package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveListTest {
    private fun a(pkg: String, size: Long = 0) = ArchiveApp(pkg, 0, emptySet(), size)
    private val labels = mapOf("com.b" to "Banana", "com.a" to "apple", "com.c" to "Cherry", "com.sys" to "System thing")
    // As BackupViewModel.label: phone data has its own label
    private val label = { p: String -> Personal.of(p)?.label ?: labels[p] ?: p }
    private val all = listOf(a("com.b", 30), a("com.a", 10), a("com.c", 20), a("com.sys", 5), a(Personal.SMS.target, 1))
    private val installed = mapOf(
        "com.a" to ArchiveList.Installed(system = false, installedAt = 300),
        "com.b" to ArchiveList.Installed(system = false, installedAt = 100),
        "com.sys" to ArchiveList.Installed(system = true, installedAt = 1),
    ) // com.c is not installed

    private fun v(query: String = "", system: Boolean = false, inst: Boolean = true, notInst: Boolean = true,
                  sort: AppSort = AppSort.NAME, asc: Boolean = true) =
        ArchiveList.visible(all, installed, label, query, system, inst, notInst, sort, asc).map { it.packageName }

    @Test fun `phone data first, system apps hidden, apps by name ignoring case`() =
        assertEquals(listOf(Personal.SMS.target, "com.a", "com.b", "com.c"), v())

    @Test fun `system apps appear when asked for`() =
        assertEquals(listOf(Personal.SMS.target, "com.a", "com.b", "com.c", "com.sys"), v(system = true))

    @Test fun `installed only and not installed only`() {
        assertEquals(listOf(Personal.SMS.target, "com.a", "com.b"), v(notInst = false))
        assertEquals(listOf(Personal.SMS.target, "com.c"), v(inst = false))
    }

    @Test fun `phone data is not touched by the installed filters or the sort direction`() {
        assertEquals(listOf(Personal.SMS.target), v(inst = false, notInst = false))
        assertEquals(listOf(Personal.SMS.target, "com.c", "com.b", "com.a"), v(asc = false))
    }

    @Test fun `sorts by backup size and by installation time, not installed last when ascending`() {
        assertEquals(listOf(Personal.SMS.target, "com.a", "com.c", "com.b"), v(sort = AppSort.DATA_SIZE))
        assertEquals(listOf(Personal.SMS.target, "com.c", "com.b", "com.a"), v(sort = AppSort.INSTALL_TIME))
    }

    @Test fun `search matches the label or the package`() {
        assertEquals(listOf("com.b"), v(query = "bana"))
        assertEquals(listOf("com.c"), v(query = "COM.C"))
        assertEquals(listOf(Personal.SMS.target), v(query = Personal.SMS.label.take(3)))
    }
}
