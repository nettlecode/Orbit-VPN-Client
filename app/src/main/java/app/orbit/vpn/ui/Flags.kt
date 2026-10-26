package app.orbit.vpn.ui

/** Guesses a country flag emoji from a server name ("🇩🇪 Germany", "Frankfurt-DE", "Москва"). */
object Flags {
    private val COUNTRIES = mapOf(
        "ru" to listOf("russia", "россия", "moscow", "москва", "saint petersburg", "петербург"),
        "de" to listOf("germany", "германия", "frankfurt", "франкфурт", "berlin", "берлин", "nuremberg"),
        "nl" to listOf("netherlands", "нидерланды", "голландия", "holland", "amsterdam", "амстердам"),
        "fi" to listOf("finland", "финляндия", "helsinki", "хельсинки"),
        "us" to listOf("usa", "united states", "сша", "америка", "america", "new york", "нью-йорк", "los angeles"),
        "gb" to listOf("united kingdom", "великобритания", "англия", "england", "london", "лондон", "britain"),
        "fr" to listOf("france", "франция", "paris", "париж"),
        "se" to listOf("sweden", "швеция", "stockholm", "стокгольм"),
        "pl" to listOf("poland", "польша", "warsaw", "варшава"),
        "tr" to listOf("turkey", "türkiye", "турция", "istanbul", "стамбул"),
        "kz" to listOf("kazakhstan", "казахстан", "almaty", "алматы", "astana", "астана"),
        "jp" to listOf("japan", "япония", "tokyo", "токио"),
        "sg" to listOf("singapore", "сингапур"),
        "ca" to listOf("canada", "канада", "toronto", "торонто"),
        "ch" to listOf("switzerland", "швейцария", "zurich", "цюрих"),
        "at" to listOf("austria", "австрия", "vienna", "вена"),
        "lv" to listOf("latvia", "латвия", "riga", "рига"),
        "lt" to listOf("lithuania", "литва", "vilnius", "вильнюс"),
        "ee" to listOf("estonia", "эстония", "tallinn", "таллин"),
        "am" to listOf("armenia", "армения", "yerevan", "ереван"),
        "ge" to listOf("georgia", "грузия", "tbilisi", "тбилиси"),
        "ae" to listOf("emirates", "оаэ", "uae", "dubai", "дубай"),
        "hk" to listOf("hong kong", "гонконг"),
        "es" to listOf("spain", "испания", "madrid", "мадрид"),
        "it" to listOf("italy", "италия", "milan", "милан"),
        "no" to listOf("norway", "норвегия", "oslo", "осло"),
        "cz" to listOf("czech", "чехия", "prague", "прага"),
        "rs" to listOf("serbia", "сербия", "belgrade", "белград"),
        "md" to listOf("moldova", "молдова", "chisinau"),
        "ro" to listOf("romania", "румыния", "bucharest"),
        "bg" to listOf("bulgaria", "болгария", "sofia"),
        "il" to listOf("israel", "израиль", "tel aviv"),
        "in" to listOf("india", "индия", "mumbai"),
        "kr" to listOf("korea", "корея", "seoul", "сеул"),
        "au" to listOf("australia", "австралия", "sydney", "сидней"),
        "br" to listOf("brazil", "бразилия", "sao paulo"),
        "ua" to listOf("ukraine", "украина", "kyiv", "киев"),
        "by" to listOf("belarus", "беларусь", "minsk", "минск"),
        "cy" to listOf("cyprus", "кипр"),
    )

    /** Two-letter codes recognised as standalone upper-case tokens ("Frankfurt DE", "US-1"). */
    private val CODE_TOKEN = Regex("""(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])""")
    private val CODE_ALIASES = mapOf("UK" to "gb")

    private val nameMatchers = COUNTRIES.mapValues { (_, words) ->
        words.map { Regex("""(^|[^\p{L}])${Regex.escape(it)}($|[^\p{L}])""") }
    }

    fun emojiFor(name: String): String? {
        existingFlag(name)?.let { return it }
        val lower = name.lowercase()
        nameMatchers.forEach { (code, regexes) ->
            if (regexes.any { it.containsMatchIn(lower) }) return emoji(code)
        }
        CODE_TOKEN.findAll(name).forEach { match ->
            val token = match.groupValues[1]
            val code = CODE_ALIASES[token] ?: token.lowercase()
            if (code in COUNTRIES) return emoji(code)
        }
        return null
    }

    private fun existingFlag(name: String): String? {
        val points = name.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            if (points[i] in REGIONAL && points[i + 1] in REGIONAL) return String(points, i, 2)
        }
        return null
    }

    /** Name with a leading flag emoji removed, since the flag is drawn separately. */
    fun stripFlag(name: String): String {
        val flag = existingFlag(name) ?: return name
        return name.replace(flag, "").trim().trimStart('-', '|', '·', ' ').ifEmpty { name }
    }

    private fun emoji(code: String): String {
        val points = code.uppercase().map { 0x1F1E6 + (it - 'A') }.toIntArray()
        return String(points, 0, points.size)
    }

    private val REGIONAL = 0x1F1E6..0x1F1FF
}
