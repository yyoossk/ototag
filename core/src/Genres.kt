package com.yyoo.ototag.core

/**
 * The user's own genre list, kept next to the built-in TagData.GENRES.
 * Stored as one genre per line in a single preference value; the
 * platforms own the storage, this object owns the shape.
 */
object GenreList {

    fun parse(stored: String?): List<String> =
        stored?.split("\n")?.map { it.trim() }
            ?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

    fun serialize(list: List<String>): String = list.joinToString("\n")

    /** Adds [genre] unless it is blank, already saved, or a built-in. */
    fun add(list: List<String>, genre: String): List<String> {
        val g = genre.trim()
        if (g.isBlank() || g in list || g in TagData.GENRES) return list
        return list + g
    }

    fun remove(list: List<String>, genre: String): List<String> = list - genre

    /** The built-in genres the user has not removed. */
    fun visibleDefaults(hidden: List<String>): List<String> =
        TagData.GENRES.filter { it !in hidden }

    /** Removes a built-in genre (it can be restored later). */
    fun hide(hidden: List<String>, genre: String): List<String> =
        if (genre in hidden || genre !in TagData.GENRES) hidden else hidden + genre

    /** Brings a removed built-in genre back. */
    fun unhide(hidden: List<String>, genre: String): List<String> = hidden - genre
}
