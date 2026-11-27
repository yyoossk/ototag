package com.yyoo.ototag.core

/**
 * Which search sources are enabled. The apps load these from their
 * settings at startup and update them when the user flips a switch;
 * every source defaults to on.
 */
object SearchSources {
    @Volatile var mb: Boolean = true
    @Volatile var animeThemes: Boolean = true
    @Volatile var itunes: Boolean = true
    @Volatile var deezer: Boolean = true
    @Volatile var vgmdb: Boolean = true
    @Volatile var audius: Boolean = true
    @Volatile var ndl: Boolean = true
}
