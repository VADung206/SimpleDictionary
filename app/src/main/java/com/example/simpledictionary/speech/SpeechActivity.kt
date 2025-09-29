package com.example.simpledictionary.speech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.simpledictionary.R
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class SpeechActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecognized: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var tvScores: TextView
    private lateinit var etTarget: EditText

    private lateinit var vosk: VoskRecognizerHelper

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStt()
        else Toast.makeText(this, "Cần quyền micro để ghi âm", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speech)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvRecognized = findViewById(R.id.tvRecognized)
        tvTranslated = findViewById(R.id.tvTranslated)
        tvScores = findViewById(R.id.tvScores)
        etTarget = findViewById(R.id.etTargetSentence)

        vosk = VoskRecognizerHelper(this)

        btnStart.setOnClickListener {
            // xin quyền micro nếu chưa có
            if (checkSelfPermissionCompat(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else startStt()
        }

        btnStop.setOnClickListener {
            btnStop.isEnabled = false
            val state = vosk.stop()
            when (state) {
                is SttState.Result -> {
                    tvStatus.text = "Trạng thái: đã dừng"
                    tvRecognized.text = state.text.ifBlank { "(trống)" }
                    translateThenScore(state.text, state.words)
                }
                is SttState.Error -> {
                    tvStatus.text = "Lỗi: ${state.message}"
                }
                else -> Unit
            }
            btnStart.isEnabled = true
        }
    }

    private fun startStt() {
        tvRecognized.text = ""
        tvTranslated.text = ""
        tvScores.text = ""
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Trạng thái: đang ghi…"

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            vosk.start("vosk/model")
        } catch (e: UnsatisfiedLinkError) {
            Toast.makeText(this, "Thiếu native lib Vosk cho ABI: ${e.message}", Toast.LENGTH_LONG).show()
            tvStatus.text = "Lỗi: native lib"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            return
        } catch (e: Exception) {
            Toast.makeText(this, "Không load được model: ${e.message}", Toast.LENGTH_LONG).show()
            tvStatus.text = "Lỗi khởi tạo model"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            return
        }
    }

    private fun translateThenScore(recognized: String, wordTimes: List<WordTime>) {
        if (recognized.isBlank()) {
            tvTranslated.text = "(không có gì để dịch)"
            tvScores.text = ""
            return
        }
        // 1) Detect language
        val langId = LanguageIdentification.getClient()
        langId.identifyLanguage(recognized)
            .addOnSuccessListener { tag ->
                val src = TranslateLanguage.fromLanguageTag(tag ?: "en") ?: TranslateLanguage.ENGLISH
                val dst = TranslateLanguage.VIETNAMESE
                val options = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
                val translator = Translation.getClient(options)

                tvStatus.text = "Đang dịch ($tag → vi)… tải model nếu cần…"

                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(recognized)
                            .addOnSuccessListener { translated ->
                                tvTranslated.text = translated

                                // 2) Scoring (nếu người dùng nhập câu mẫu)
                                val target = etTarget.text?.toString().orEmpty()
                                if (target.isNotBlank()) {
                                    val sc = scorePronunciation(target, recognized, wordTimes)
                                    tvScores.text = buildString {
                                        appendLine("Accuracy: ${"%.0f".format(sc.accuracy*100)}%")
                                        appendLine("Fluency: ${"%.0f".format(sc.fluency*100)}%")
                                        appendLine("Completeness: ${"%.0f".format(sc.completeness*100)}%")
                                        appendLine("Tổng: ${"%.0f".format(sc.total*100)}%")
                                    }
                                } else {
                                    tvScores.text = "(Nhập câu mẫu để chấm điểm phát âm)"
                                }
                                tvStatus.text = "Hoàn tất."
                            }
                            .addOnFailureListener { e ->
                                tvTranslated.text = "Lỗi dịch: ${e.message}"
                                tvStatus.text = "Lỗi dịch."
                            }
                    }
                    .addOnFailureListener { e ->
                        tvTranslated.text = "Không tải được model dịch: ${e.message}"
                        tvStatus.text = "Lỗi tải model."
                    }
            }
            .addOnFailureListener { e ->
                tvTranslated.text = "Không xác định được ngôn ngữ: ${e.message}"
                tvStatus.text = "Lỗi detect language."
            }
    }

    private fun checkSelfPermissionCompat(permission: String): Int {
        return if (Build.VERSION.SDK_INT >= 23)
            checkSelfPermission(permission) else PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        vosk.release()
    }
}
