package app.androidwebcam.core

/** Tiny JSON helpers: the server only emits small, fixed-shape documents. */
object Json {
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> if (ch.code < 0x20 || ch == ' ' || ch == ' ') {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Builds a flat JSON object from already-encoded values (use [quote] for strings). */
    fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) -> quote(k) + ":" + v }

    private val stringFieldCache = HashMap<String, Regex>()

    /**
     * Extracts a top-level string field from a small JSON document. Only simple
     * strings without escapes are accepted, which is all the viewer ever sends.
     */
    @Synchronized
    fun stringField(json: String, name: String, maxLength: Int = 128): String? {
        val regex = stringFieldCache.getOrPut(name) {
            Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"\\\\\\u0000-\\u001f]*)\"")
        }
        val value = regex.find(json)?.groupValues?.get(1) ?: return null
        return if (value.length <= maxLength) value else null
    }
}
