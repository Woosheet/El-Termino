package dev.eltermino.app

private val placeholder = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")

/** The `{{name}}` placeholders in [command], each once, in order of appearance. */
fun snippetVariables(command: String): List<String> =
    placeholder.findAll(command).map { it.groupValues[1] }.distinct().toList()

/** [command] with its placeholders replaced; names missing from [values] stay as they are. */
fun fillSnippet(command: String, values: Map<String, String>): String =
    placeholder.replace(command) { match -> values[match.groupValues[1]] ?: match.value }

/** Where a snippet runs. */
sealed interface SnippetTarget {
    /** The tab with the keyboard; with broadcast on, its broadcast group too. */
    data object Focused : SnippetTarget

    data class Tabs(val tabIds: Set<String>) : SnippetTarget

    /** Opens a new tab on each host and runs the snippet there once it's connected. */
    data class Hosts(val hostIds: List<String>) : SnippetTarget
}
