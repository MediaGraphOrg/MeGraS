package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DayOfWeekFunction : FunctionBase1() {
    override fun exec(arg: NodeValue): NodeValue {
        val dateStr = arg.asNode().literal.value.toString()
        val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
        // ISO: 1=Monday, 7=Sunday
        val dayOfWeek = date.dayOfWeek.value
        return NodeValue.makeInteger(dayOfWeek.toLong())
    }
}

