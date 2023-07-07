package org.megras.lang

import com.jakewharton.picnic.table
import org.megras.data.graph.QuadValue

data class ResultTable(val rows: List<Map<String, QuadValue>>) {

    val headers: Set<String>

    init {
        this.headers = rows.flatMapTo(HashSet()) {
            it.keys
        }
    }

    override fun toString(): String {
        val headerList = this.headers.toList()
        return table {
            cellStyle {
                border = true
                paddingLeft = 1
                paddingRight = 1
            }
            header {
                row(*headerList.toTypedArray())
            }
            body {
                rows.forEach {r ->
                    row(*headerList.map { r[it] ?: "null" }.toTypedArray())
                }
            }
        }.toString()
    }
}