package com.ibaadat.invoicescanner

/** One parsed field plus how much the parser trusts it. */
data class Field(val value: String, val confidence: Float) {
    val found get() = value.isNotBlank()
}

data class Parsed(val invoice: Field, val party: Field, val date: Field)

/**
 * Pulls invoice number, party name and invoice date out of raw OCR text.
 *
 * Layout assumption (matches the Ibaadat GST invoice):
 *   "Invoice No. : P2CRS26/3171"   top right
 *   "Invoice Date : 19-11-2026"    below it
 *   party name on the first real line under "Billing Address"
 * Each rule falls back to a looser one, and the confidence drops when it does,
 * which is what drives the "check this" badge on screen.
 */
object Fields {

    private val NOISE = Regex(
        "address|locality|pin\\s*code|state\\s*code|phone|gstn|city|invoice|" +
        "original|buyer|copy|shipping|billing|pos\\b|irn",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): Parsed {
        val lines = raw.split("\n").map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        return Parsed(invoice(raw, lines), party(lines), date(raw, lines))
    }

    private fun invoice(raw: String, lines: List<String>): Field {
        Regex("Invoice\\s*No[^A-Za-z0-9]{0,12}([A-Z0-9][A-Z0-9/\\-]{3,24})", RegexOption.IGNORE_CASE)
            .find(raw)?.let { return Field(tidyInv(it.groupValues[1]), 0.92f) }

        Regex("\\b([A-Z]{1,4}\\d?[A-Z]{0,4}\\d{2}/\\d{3,6})\\b")
            .find(lines.joinToString(" | "))?.let { return Field(tidyInv(it.groupValues[1]), 0.6f) }

        return Field("", 0f)
    }

    private fun tidyInv(s: String) = s.uppercase().replace(Regex("[^A-Z0-9/\\-]"), "")

    private fun date(raw: String, lines: List<String>): Field {
        Regex("Invoice\\s*Date[^0-9]{0,12}(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})", RegexOption.IGNORE_CASE)
            .find(raw)?.let { return Field(normalise(it.groupValues[1]), 0.92f) }

        Regex("\\b(\\d{2}[-/.]\\d{2}[-/.]\\d{4})\\b")
            .find(lines.joinToString(" | "))?.let { return Field(normalise(it.groupValues[1]), 0.5f) }

        return Field("", 0f)
    }

    private fun normalise(d: String): String {
        val p = d.replace(Regex("[./]"), "-").split("-")
        if (p.size != 3) return d
        var y = p[2]
        if (y.length == 2) y = (if ((y.toIntOrNull() ?: 0) > 70) "19" else "20") + y
        return "%02d-%02d-%s".format(p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0, y)
    }

    private fun party(lines: List<String>): Field {
        // Preferred: the first usable line under "Billing Address".
        listOf(
            Regex("billing\\s*address", RegexOption.IGNORE_CASE) to 0.88f,
            Regex("shipping\\s*address", RegexOption.IGNORE_CASE) to 0.7f
        ).forEach { (anchor, conf) ->
            val i = lines.indexOfFirst { anchor.containsMatchIn(it) }
            if (i >= 0) {
                for (j in i + 1 until minOf(i + 6, lines.size)) {
                    val n = nameOf(lines[j])
                    if (n != null) return Field(n, conf)
                }
            }
        }
        // Fallback: the line directly above a street line.
        val i = lines.indexOfFirst {
            Regex("shop\\s*no|chawl|marg|plot\\s*no|road", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        if (i > 0) nameOf(lines[i - 1])?.let { return Field(it, 0.4f) }

        return Field("", 0f)
    }

    private fun nameOf(line: String): String? {
        var s = line.trim().trimStart { !it.isLetter() }.trimEnd(':', '|', ' ')
        if (s.length !in 5..60) return null
        if (NOISE.containsMatchIn(s)) return null
        if (s.count { it.isLetter() } < s.length * 0.65) return null
        if (s.split(" ").filter { it.isNotBlank() }.size < 2) return null
        return s.split(" ").joinToString(" ") { w ->
            if (w.length > 1) w[0].uppercase() + w.substring(1).lowercase() else w.uppercase()
        }
    }

    /** Safe, predictable file name: INVOICE_PARTY_DATE.pdf */
    fun fileName(invoice: String, party: String, date: String): String {
        fun chunk(s: String, fallback: String): String {
            val c = s.trim().replace(Regex("[/\\\\:*?\"<>|]+"), "-")
                .replace(Regex("\\s+"), "-").replace(Regex("-+"), "-").trim('-')
            return if (c.isBlank()) fallback else c
        }
        return "${chunk(invoice, "NO-INV")}_${chunk(party, "NO-PARTY")}_${chunk(date, "NO-DATE")}.pdf"
    }
}
