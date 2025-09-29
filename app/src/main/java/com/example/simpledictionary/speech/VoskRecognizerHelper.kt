package com.example.simpledictionary.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// ==== Kiểu dữ liệu khớp với SpeechActivity ====
data class WordTime(
    val word: String,
    val start: Float,   // giây
    val end: Float      // giây
)

sealed class SttState {
    data object Idle : SttState()
    data class Result(val text: String, val words: List<WordTime>) : SttState()
    data class Error(val message: String) : SttState()
}

// ==== Helper thu âm + Vosk ====
class VoskRecognizerHelper(
    private val ctx: Context
) : Closeable {

    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    private var readJob: Job? = null
    private var isRecording = false

    private var lastText: String = ""
    private var lastWords: List<WordTime> = emptyList()

    /**
     * Bắt đầu STT.
     * @param assetModelDir thư mục model nằm trong assets (vd: "vosk/model")
     * @param sampleRate Hz (mặc định 16000)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(assetModelDir: String, sampleRate: Int = 16000) {
        if (isRecording) return

        // Bảo vệ: nếu Activity chưa cấp quyền thì dừng sớm
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(ctx, "Thiếu quyền micro (RECORD_AUDIO)", Toast.LENGTH_SHORT).show()
            return
        }

        // 1) Chuẩn bị model: copy từ assets sang bộ nhớ trong (Vosk cần đường dẫn thực)
        val modelDir = ensureModelExtracted(assetModelDir)

        try {
            if (model == null) model = Model(modelDir.absolutePath)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "Không load được model: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 2) Tạo Recognizer
        try {
            recognizer = Recognizer(model, sampleRate.toFloat())
        } catch (t: Throwable) {
            Toast.makeText(ctx, "Không khởi tạo được Recognizer: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 3) Tạo AudioRecord an toàn
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding).coerceAtLeast(4096)

        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // hoặc MIC
                sampleRate,
                channel,
                encoding,
                minBuf
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(ctx, "Không khởi tạo được micro", Toast.LENGTH_SHORT).show()
                rec.release()
                return
            }

            audioRecord = rec
            rec.startRecording()
            isRecording = true
            lastText = ""
            lastWords = emptyList()

            readJob = CoroutineScope(Dispatchers.Default).launch {
                val buf = ShortArray(minBuf / 2)
                while (isRecording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val r = recognizer ?: break
                        if (r.acceptWaveForm(buf, n)) {
                            // Khi có 1 "kết quả đầy đủ", lưu tạm
                            parseResultJson(r.result)?.let { (t, ws) ->
                                lastText = t
                                lastWords = ws
                            }
                        } else {
                            // có thể dùng r.partialResult nếu muốn hiển thị realtime
                        }
                    }
                }
                // kết thúc vòng đọc — nếu còn finalResult thì lấy thêm
                recognizer?.finalResult?.let { json ->
                    parseResultJson(json)?.let { (t, ws) ->
                        lastText = t
                        lastWords = ws
                    }
                }
            }
        } catch (se: SecurityException) {
            // Phòng trường hợp quyền bị thu hồi giữa chừng
            Toast.makeText(ctx, "Bị chặn quyền micro: ${se.message}", Toast.LENGTH_LONG).show()
            stop()
        } catch (t: Throwable) {
            Toast.makeText(ctx, "Lỗi ghi âm: ${t.message}", Toast.LENGTH_LONG).show()
            stop()
        }
    }

    /**
     * Dừng STT và trả về kết quả cuối cùng (text + words).
     */
    fun stop(): SttState {
        return try {
            isRecording = false
            readJob?.cancel()
            readJob = null

            audioRecord?.run {
                try { stop() } catch (_: Throwable) {}
                release()
            }
            audioRecord = null

            // Lấy finalResult lần cuối
            recognizer?.finalResult?.let { json ->
                parseResultJson(json)?.let { (t, ws) ->
                    lastText = t
                    lastWords = ws
                }
            }

            SttState.Result(lastText, lastWords)
        } catch (t: Throwable) {
            SttState.Error(t.message ?: "stop() failed")
        }
    }

    override fun close() = release()

    fun release() {
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    // ====== Utils ======

    /**
     * Copy model từ assets/assetModelDir sang /files/vosk_models/<safeName> nếu chưa tồn tại.
     */
    private fun ensureModelExtracted(assetModelDir: String): File {
        val outRoot = File(ctx.filesDir, "vosk_models")
        if (!outRoot.exists()) outRoot.mkdirs()

        val safeName = assetModelDir.replace('/', '_')
        val outDir = File(outRoot, safeName)

        if (File(outDir, ".done").exists()) {
            return outDir
        }

        copyAssetFolder(assetModelDir, outDir)
        File(outDir, ".done").writeText("ok")
        return outDir
    }

    private fun copyAssetFolder(assetPath: String, outDir: File) {
        try {
            val am = ctx.assets
            val list = am.list(assetPath) ?: emptyArray()

            if (list.isEmpty()) {
                // là file
                copyAssetFile(assetPath, outDir)
            } else {
                if (!outDir.exists()) outDir.mkdirs()
                for (name in list) {
                    val childIn = if (assetPath.isEmpty()) name else "$assetPath/$name"
                    val childOut = File(outDir, name)
                    copyAssetFolder(childIn, childOut) // đệ quy
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(
                "Copy assets failed at $assetPath -> ${outDir.absolutePath}: ${e.message}",
                e
            )
        }
    }

    private fun copyAssetFile(assetFile: String, outFile: File) {
        outFile.parentFile?.mkdirs()
        ctx.assets.open(assetFile).use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
    }

    private fun parseResultJson(json: String?): Pair<String, List<WordTime>>? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val text = obj.optString("text", "")
            val words = mutableListOf<WordTime>()
            val arr = obj.optJSONArray("result")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    words += WordTime(
                        word = w.optString("word", ""),
                        start = w.optDouble("start", 0.0).toFloat(),
                        end = w.optDouble("end", 0.0).toFloat()
                    )
                }
            }
            text to words
        } catch (t: Throwable) {
            Log.w("VoskHelper", "parse json failed: ${t.message}")
            null
        }
    }
}
