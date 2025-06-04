package com.example.simpledictionary

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var currentWord: String = ""
    private lateinit var dictionaryEntries: List<DictionaryEntry>
    private lateinit var historyAdapter: ArrayAdapter<String>
    private val favoriteWords = mutableSetOf<String>()
    private val searchHistory = mutableListOf<String>()
    private lateinit var speakButton: Button
    private lateinit var favButton: Button
    private lateinit var favoriteListButton: Button
    private lateinit var tvSynonyms: TextView
    private lateinit var tvAntonyms: TextView
    private lateinit var prefs: SharedPreferences

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSynonyms = findViewById(R.id.textViewSynonyms)
        tvAntonyms = findViewById(R.id.textViewAntonyms)
        speakButton = findViewById(R.id.speak_button)
        favButton = findViewById(R.id.favorite_button)
        favoriteListButton = findViewById(R.id.show_favorites_button)
        val searchButton = findViewById<Button>(R.id.search_button)
        val resultView = findViewById<TextView>(R.id.textViewDefinition)
        val autoCompleteTextView = findViewById<AutoCompleteTextView>(R.id.search_input)
        val historyListView = findViewById<ListView>(R.id.history_list)

        val sharedPref = getSharedPreferences("settings", MODE_PRIVATE)
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        val darkModeSwitch = findViewById<Switch>(R.id.dark_mode_switch)
        darkModeSwitch.isChecked = isDarkMode
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        prefs = getSharedPreferences("dictionary_prefs", MODE_PRIVATE)
        favoriteWords.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())

        tts = TextToSpeech(this, this)

        dictionaryEntries = DictionaryLoader.loadDictionary(this)
        val words = dictionaryEntries.map { it.word }

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            words.toMutableList()
        ) {
            override fun getFilter(): Filter {
                return object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        if (!constraint.isNullOrBlank()) {
                            val filtered = words.filter {
                                it.startsWith(constraint.toString(), ignoreCase = true)
                            }
                            results.values = filtered
                            results.count = filtered.size
                        }
                        return results
                    }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        clear()
                        if (results?.values != null) {
                            @Suppress("UNCHECKED_CAST")
                            addAll(results.values as List<String>)
                        }
                        notifyDataSetChanged()
                    }

                    override fun convertResultToString(resultValue: Any?): CharSequence {
                        return resultValue as String
                    }
                }
            }
        }

        autoCompleteTextView.setAdapter(adapter)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, searchHistory)
        historyListView.adapter = historyAdapter

        autoCompleteTextView.setOnItemClickListener { _, _, position, _ ->
            val selectedWord = adapter.getItem(position)?.lowercase()
            searchAndDisplay(selectedWord, resultView)
        }

        autoCompleteTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                historyListView.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        searchButton.setOnClickListener {
            val query = autoCompleteTextView.text.toString().trim().lowercase()
            currentWord = query
            searchAndDisplay(query, resultView)
        }

        historyListView.setOnItemClickListener { _, _, position, _ ->
            val word = searchHistory[position]
            autoCompleteTextView.setText(word)
            currentWord = word
            searchAndDisplay(word, resultView)
        }

        speakButton.setOnClickListener {
            if (currentWord.isNotEmpty()) {
                speak(currentWord)
            } else {
                Toast.makeText(this, "Không có từ để phát âm", Toast.LENGTH_SHORT).show()
            }
        }

        favButton.setOnClickListener {
            if (currentWord.isNotEmpty()) {
                favoriteWords.add(currentWord)
                saveFavorites()
                Toast.makeText(this, "Đã thêm vào yêu thích", Toast.LENGTH_SHORT).show()
            }
        }

        favoriteListButton.setOnClickListener {
            val intent = Intent(this, FavoriteActivity::class.java)
            startActivity(intent)
        }
    }

    private fun searchAndDisplay(query: String?, resultView: TextView) {
        if (query.isNullOrBlank()) return

        val words = query.split(",", " ", ";")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (words.size > 1) {
            AlertDialog.Builder(this)
                .setTitle("Dịch nhiều từ")
                .setMessage("Nếu dịch tiếp sẽ không hiện từ đồng nghĩa trái nghĩa. Bạn có muốn dịch tất cả ${words.size} từ không?")
                .setPositiveButton("Có") { _, _ ->
                    val results = StringBuilder()
                    for (word in words) {
                        val entry = dictionaryEntries.find { it.word == word }
                        if (entry != null) {
                            results.append("• $word: ${entry.definition}\n\n")
                            if (!searchHistory.contains(word)) {
                                searchHistory.add(0, word)
                            }
                        } else {
                            results.append("• $word: Không tìm thấy\n\n")
                        }
                    }
                    resultView.text = results.trim().toString()
                    tvSynonyms.text = "Đồng nghĩa:"
                    tvAntonyms.text = "Trái nghĩa:"
                    historyAdapter.notifyDataSetChanged()
                }
                .setNegativeButton("Không", null)
                .show()
            return
        }

        val word = words.first()
        val entry = dictionaryEntries.find { it.word == word }

        if (entry != null) {
            resultView.text = entry.definition
            tvSynonyms.text = "Đồng nghĩa: (đang tải...)"
            tvAntonyms.text = "Trái nghĩa: (đang tải...)"

            if (!searchHistory.contains(word)) {
                searchHistory.add(0, word)
                historyAdapter.notifyDataSetChanged()
            }

            DictionaryEnricher.enrichWithSynonymsAntonyms(entry) { enriched ->
                runOnUiThread {
                    tvSynonyms.text = "Đồng nghĩa: ${if (enriched.synonyms.isEmpty()) "Không có" else enriched.synonyms.joinToString(", ")}"
                    tvAntonyms.text = "Trái nghĩa: ${if (enriched.antonyms.isEmpty()) "Không có" else enriched.antonyms.joinToString(", ")}"
                }
            }

            currentWord = word
        } else {
            resultView.text = "Không tìm thấy từ '$word'"
            tvSynonyms.text = "Đồng nghĩa:"
            tvAntonyms.text = "Trái nghĩa:"
        }
    }

    private fun saveFavorites() {
        prefs.edit().putStringSet("favorites", favoriteWords).apply()
    }

    private fun speak(word: String) {
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Ngôn ngữ không được hỗ trợ!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "TTS không khả dụng!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
