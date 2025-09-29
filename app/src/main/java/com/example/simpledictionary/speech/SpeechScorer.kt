package com.example.simpledictionary.speech

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PronunciationScore(
    val accuracy: Double,
    val fluency: Double,
    val completeness: Double,
    val total: Double
)

/**
 * Chấm điểm đơn giản:
 * - accuracy: trùng khớp từ (theo lowercase) giữa target và recognized
 * - fluency: dựa vào tỉ lệ (tổng duration / số từ nói) so với khoảng 0.18s~0.6s/từ (đơn giản hoá)
 * - completeness: tỷ lệ số từ target xuất hiện trong recognized
 */
fun scorePronunciation(target: String, recognized: String, wordTimes: List<WordTime>): PronunciationScore {
    val tgtWords = target.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val recWords = recognized.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    if (tgtWords.isEmpty()) return PronunciationScore(0.0, 0.0, 0.0, 0.0)

    // accuracy & completeness
    val setTarget = tgtWords.toMutableList()
    var hit = 0
    for (w in recWords) {
        val idx = setTarget.indexOf(w)
        if (idx >= 0) {
            hit++
            setTarget.removeAt(idx)
        }
    }
    val accuracy = if (recWords.isNotEmpty()) hit.toDouble() / recWords.size else 0.0
    val completeness = hit.toDouble() / tgtWords.size

    // fluency: duration trung bình / từ, khuyến nghị 0.18–0.6s/từ (tuỳ ngữ tốc)
    val duration = if (wordTimes.isNotEmpty())
        (wordTimes.maxOf { it.end } - wordTimes.minOf { it.start }).toDouble()
    else 0.0
    val wordsSpoken = max(1, recWords.size)
    val avgPerWord = if (duration > 0) duration / wordsSpoken else 0.4
    // map 0.18..0.6 -> 1.0..0.6, ngoài khoảng giảm mạnh
    val fluency = when {
        avgPerWord <= 0.18 -> 0.6
        avgPerWord >= 0.9 -> 0.2
        else -> 1.2 - avgPerWord // 0.18->1.02 ~1.0, 0.6->0.6, 0.9->0.3
    }.coerceIn(0.0, 1.0)

    val total = (accuracy * 0.45) + (fluency * 0.25) + (completeness * 0.30)
    return PronunciationScore(accuracy, fluency, completeness, total)
}
