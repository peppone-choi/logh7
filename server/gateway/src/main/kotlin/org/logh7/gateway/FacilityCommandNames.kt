package org.logh7.gateway

/** Stable command IDs and editable Korean labels share one resource. Author: Peppone Choi. */
internal object FacilityCommandNames {
    private val names:Map<Int,String> by lazy {
        checkNotNull(javaClass.getResourceAsStream("/l10n/command-labels.ko.tsv")).bufferedReader(Charsets.UTF_8).useLines {lines->
            val rows=lines.filter {it.isNotBlank() && !it.startsWith('#')}.map {line->
                val fields=line.split('\t');require(fields.size==2 && fields[1].isNotBlank())
                fields[0].toInt() to fields[1]
            }.toList()
            require(rows.map {it.first}.distinct().size==rows.size);rows.toMap()
        }
    }
    fun name(id:Int):String=checkNotNull(names[id]) {"Missing command display name: $id"}
}
