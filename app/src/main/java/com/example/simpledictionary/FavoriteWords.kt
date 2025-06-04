package com.example.simpledictionary

import android.content.Context

object FavoriteWords {
    fun getFavoriteWords(context: Context): List<String> {
        val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("favorites", emptySet())?.toList() ?: emptyList()
    }

    fun saveFavoriteWords(context: Context, favorites: Set<String>) {
        val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun removeFavorite(context: Context, word: String) {
        val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(word)
        prefs.edit().putStringSet("favorites", currentSet).apply()
    }
}
