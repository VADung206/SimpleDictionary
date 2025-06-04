package com.example.simpledictionary

import android.content.Context
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

// Dữ liệu chính trong từ điển
data class DictionaryEntry(
    val word: String,
    val definition: String,
)
// Dữ liệu bổ sung qua API
data class EnrichedEntry(
    val synonyms: List<String>,
    val antonyms: List<String>
)

// Nạp từ điển từ file anhViet.txt
object DictionaryLoader {
    fun loadDictionary(context: Context): List<DictionaryEntry> {
        val inputStream = context.assets.open("anhViet.txt")
        val lines = inputStream.bufferedReader().readLines()

        val entries = mutableListOf<DictionaryEntry>()
        var currentWord: String? = null
        val currentDefinition = StringBuilder()

        for (line in lines + "") {
            val trimmed = line.trim()

            if (trimmed.startsWith("@")) {
                if (currentWord != null && currentDefinition.isNotEmpty()) {
                    entries.add(DictionaryEntry(currentWord!!, currentDefinition.toString().trim()))
                    currentDefinition.clear()
                }

                val rawLine = trimmed.removePrefix("@")
                val wordOnly = rawLine.split("/", limit = 2).firstOrNull()?.trim()?.lowercase() ?: "unknown"
                currentWord = wordOnly
                currentDefinition.appendLine(rawLine)
            } else if (trimmed.isBlank()) {
                if (currentWord != null && currentDefinition.isNotEmpty()) {
                    entries.add(DictionaryEntry(currentWord!!, currentDefinition.toString().trim()))
                    currentWord = null
                    currentDefinition.clear()
                }
            } else {
                currentDefinition.appendLine(trimmed)
            }
        }

        return entries
    }
}

// Enricher: gọi API để lấy từ đồng nghĩa/trái nghĩa
object DictionaryEnricher {
    fun enrichWithSynonymsAntonyms(entry: DictionaryEntry, callback: (EnrichedEntry) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val synUrl = URL("https://api.datamuse.com/words?rel_syn=${entry.word}")
                val antUrl = URL("https://api.datamuse.com/words?rel_ant=${entry.word}")

                val synonyms = fetchWords(synUrl)
                val antonyms = fetchWords(antUrl)

                withContext(Dispatchers.Main) {
                    callback(EnrichedEntry(synonyms, antonyms))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(EnrichedEntry(emptyList(), emptyList()))
                }
            }
        }
    }

    private fun fetchWords(url: URL): List<String> {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val response = conn.inputStream.bufferedReader().readText()
        val jsonArray = org.json.JSONArray(response)
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getJSONObject(i).getString("word"))
        }
        return list
    }
}