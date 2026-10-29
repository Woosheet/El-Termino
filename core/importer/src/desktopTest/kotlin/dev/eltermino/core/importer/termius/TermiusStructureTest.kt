package dev.eltermino.core.importer.termius

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

/**
 * Not part of the normal run: with `-PtermiusStructure` it prints how Termius stores its
 * data on this computer, as store names, field names and kinds of value. No key is read and
 * no value is printed.
 */
class TermiusStructureTest {
    @Test
    fun printTheStructureOfThisComputersTermius() {
        assumeTrue(System.getProperty("termius.structure") == "true")
        val dirs = TermiusLocalReader.dataDirs()
        if (dirs.isEmpty()) println("TERMIUS: nessuna cartella di Termius trovata")
        dirs.forEach { println("TERMIUS ${it.path}\n" + TermiusLocalReader.structure(it)) }
    }
}
