package com.yyoo.ototag.core

/**
 * Session-scoped memo for search results. Tagging a whole album fires
 * near-identical searches for every track; remembering the results for
 * the session makes bulk search faster and gentler on the servers.
 * Only non-empty results are stored, so a transient failure or an
 * empty answer is always retried fresh.
 */
object SearchCache {
    private const val MAX = 64
    private val map = object : LinkedHashMap<String, Any>(MAX, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Any>
        ): Boolean = size > MAX
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = map[key] as? T

    @Synchronized
    fun put(key: String, value: Any) { map[key] = value }

    @Synchronized
    fun clear() { map.clear() }
}
