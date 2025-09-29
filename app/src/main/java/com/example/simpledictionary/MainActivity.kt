package com.example.simpledictionary

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

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

    // UI & OCR
    private lateinit var btnCamera: Button
    private lateinit var autoCompleteTextView: AutoCompleteTextView
    private lateinit var resultView: TextView

    // FileProvider URI khi chụp ảnh
    private var cameraImageUri: Uri? = null

    // ===== Permissions
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else Toast.makeText(this, "Cần quyền camera", Toast.LENGTH_SHORT).show()
        }

    private val requestReadImagesPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openGallery()
            else Toast.makeText(this, "Cần quyền đọc ảnh để chọn từ thư viện", Toast.LENGTH_SHORT).show()
        }

    // ===== Launchers
    // Camera: đọc ảnh từ EXTRA_OUTPUT (cameraImageUri)
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = cameraImageUri
                if (uri != null) {
                    try {
                        val bitmap = decodeBitmapFromUri(uri)
                        recognizeTextFromImage(bitmap)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Không đọc được ảnh camera: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Không có ảnh trả về", Toast.LENGTH_SHORT).show()
                }
            }
        }

    // Gallery: đọc ảnh từ Uri trả về
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    try {
                        val bitmap = decodeBitmapFromUri(uri)
                        recognizeTextFromImage(bitmap)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Lỗi khi tải ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(src)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

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
        resultView = findViewById(R.id.textViewDefinition)
        autoCompleteTextView = findViewById(R.id.search_input)
        val historyListView = findViewById<ListView>(R.id.history_list)
        btnCamera = findViewById(R.id.btn_camera)

        // Dark mode
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

        // Nút OCR (chụp ảnh / chọn ảnh)
        btnCamera.setOnClickListener { startImageRecognition() }

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

        // Nút luyện nói (màn SpeechActivity)
        val btnSpeech = findViewById<Button>(R.id.btn_speech)
        btnSpeech?.setOnClickListener {
            startActivity(Intent(this, com.example.simpledictionary.speech.SpeechActivity::class.java))
        }
    }

    // ================== Tra từ & enrich ==================
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

    // ================== OCR + Translate ==================
    private fun recognizeTextFromImage(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        Toast.makeText(this, "Đang nhận dạng văn bản…", Toast.LENGTH_SHORT).show()

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text.trim()
                if (recognizedText.isNotEmpty()) {
                    detectAndTranslate(recognizedText, targetLangTag = "vi") { translated, srcTag, error ->
                        if (error != null) {
                            Toast.makeText(this, "Lỗi dịch: $error", Toast.LENGTH_LONG).show()
                            autoCompleteTextView.setText(recognizedText)
                            searchAndDisplay(recognizedText, resultView)
                            return@detectAndTranslate
                        }

                        showOcrTranslateDialog(
                            original = recognizedText,
                            translated = translated ?: "",
                            detectedLang = srcTag ?: "und"
                        )
                    }
                } else {
                    Toast.makeText(this, "Không tìm thấy văn bản nào trong ảnh.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Lỗi khi nhận dạng: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun detectAndTranslate(
        sourceText: String,
        targetLangTag: String = "vi",
        callback: (translated: String?, srcTag: String?, error: String?) -> Unit
    ) {
        val langId = LanguageIdentification.getClient()
        langId.identifyLanguage(sourceText)
            .addOnSuccessListener { tag ->
                val detected = tag ?: "und"
                val src = TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
                val dst = TranslateLanguage.fromLanguageTag(targetLangTag) ?: TranslateLanguage.VIETNAMESE

                val options = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
                val translator: Translator = Translation.getClient(options)

                Toast.makeText(this, "Đang chuẩn bị model dịch (${detected}→$targetLangTag)…", Toast.LENGTH_SHORT).show()

                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(sourceText)
                            .addOnSuccessListener { translated ->
                                callback(translated, detected, null)
                            }
                            .addOnFailureListener { e ->
                                callback(null, detected, e.message ?: "Translate failed")
                            }
                    }
                    .addOnFailureListener { e ->
                        callback(null, detected, "Không thể tải model: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                callback(null, null, "Không xác định được ngôn ngữ: ${e.message}")
            }
    }

    private fun showOcrTranslateDialog(original: String, translated: String, detectedLang: String) {
        val msg = buildString {
            appendLine("Ngôn ngữ phát hiện: $detectedLang")
            appendLine()
            appendLine("Văn bản gốc:")
            appendLine(original)
            appendLine()
            appendLine("— — —")
            appendLine()
            appendLine("Bản dịch (vi):")
            appendLine(translated)
        }

        AlertDialog.Builder(this)
            .setTitle("Kết quả quét & dịch")
            .setMessage(msg)
            .setPositiveButton("Chép bản dịch") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("translation", translated))
                Toast.makeText(this, "Đã chép bản dịch", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Điền vào ô tìm kiếm") { _, _ ->
                autoCompleteTextView.setText(translated)
                // Nếu muốn tra nghĩa sau khi dịch:
                // searchAndDisplay(translated, resultView)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ================== Camera/Gallery ==================
    private fun startImageRecognition() {
        val options = arrayOf<CharSequence>("Chụp ảnh", "Chọn từ thư viện", "Hủy")
        AlertDialog.Builder(this)
            .setTitle("Chọn nguồn ảnh")
            .setItems(options) { dialog, item ->
                when (item) {
                    0 -> {
                        // Chụp ảnh
                        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            openCamera()
                        } else {
                            requestCameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                    1 -> {
                        // Chọn ảnh
                        if (Build.VERSION.SDK_INT >= 33) {
                            // Thường không bắt buộc khi dùng picker, nhưng xin để an toàn nếu bạn đọc file trực tiếp
                            requestReadImagesPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                openGallery()
                            } else {
                                requestReadImagesPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    }
                    else -> dialog.dismiss()
                }
            }.show()
    }

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { if (!exists()) mkdirs() }
            val photo = File.createTempFile("ocr_", ".jpg", dir)
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photo)

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            takePictureLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(pick)
    }
}
