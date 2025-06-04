package com.example.simpledictionary

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class FavoriteActivity : AppCompatActivity() {
    private lateinit var favoriteListView: ListView
    private lateinit var favoriteAdapter: FavoriteWordAdapter
    private val favoriteWords = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite)
        val backButton = findViewById<ImageButton>(R.id.button_back)
        backButton.setOnClickListener {
            finish()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Từ yêu thích"

        favoriteListView = findViewById(R.id.favorite_list_view)

        favoriteAdapter = FavoriteWordAdapter()
        favoriteListView.adapter = favoriteAdapter

        updateFavoriteList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateFavoriteList() {
        val savedFavorites = FavoriteWords.getFavoriteWords(this)
        favoriteWords.clear()
        favoriteWords.addAll(savedFavorites)
        favoriteAdapter.notifyDataSetChanged()
    }

    fun removeFavorite(context: Context, word: String) {
        val prefs = context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet("favorites", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(word)
        prefs.edit().putStringSet("favorites", currentSet).apply()
    }

    private fun removeFromFavorites(word: String) {
        FavoriteWords.removeFavorite(this, word)
        updateFavoriteList()
        Toast.makeText(this, "Đã xoá '$word' khỏi yêu thích", Toast.LENGTH_SHORT).show()
    }

    inner class FavoriteWordAdapter : BaseAdapter() {
        override fun getCount(): Int = favoriteWords.size
        override fun getItem(position: Int): Any = favoriteWords[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@FavoriteActivity)
                .inflate(R.layout.item_favorite_word, parent, false)

            val wordText = view.findViewById<TextView>(R.id.text_word)
            val deleteButton = view.findViewById<Button>(R.id.button_delete)

            val word = favoriteWords[position]
            wordText.text = word

            deleteButton.setOnClickListener {
                AlertDialog.Builder(this@FavoriteActivity)
                    .setTitle("Xác nhận xoá")
                    .setMessage("Bạn có chắc chắn muốn xoá '$word' khỏi danh sách yêu thích?")
                    .setPositiveButton("Có") { _, _ ->
                        removeFromFavorites(word)
                    }
                    .setNegativeButton("Không") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
            return view
        }
    }
}
