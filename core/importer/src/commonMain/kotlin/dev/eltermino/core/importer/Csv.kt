package dev.eltermino.core.importer

/** One CSV record and the 1-based line it starts on. */
internal class CsvRecord(val line: Int, val fields: List<String>)

/**
 * RFC 4180 reader: quoted fields may hold the delimiter, doubled quotes and line
 * breaks. Accepts CRLF, LF and a leading UTF-8 BOM.
 */
internal object Csv {
    fun read(text: String, delimiter: Char = ','): List<CsvRecord> {
        val input = text.removePrefix("﻿")
        val records = mutableListOf<CsvRecord>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var i = 0

        fun endField() {
            fields += field.toString()
            field.clear()
        }

        fun endRecord() {
            endField()
            records += CsvRecord(recordLine, fields.toList())
            fields.clear()
        }

        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> {
                        if (c == '\n') line++
                        field.append(c)
                    }
                }
            } else {
                when (c) {
                    // A quote only opens a quoted field at its start; elsewhere it's literal.
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(c)
                    delimiter -> endField()
                    '\r' -> Unit
                    '\n' -> {
                        endRecord()
                        line++
                        recordLine = line
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    /** Termius exports use commas; spreadsheets set to Italian save with semicolons. */
    fun guessDelimiter(text: String): Char {
        val header = text.removePrefix("﻿").lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return if (',' !in header && ';' in header) ';' else ','
    }
}
