package com.spake.brony.doubaoassistant.speech.kws

import android.content.res.AssetManager
import com.spake.brony.doubaoassistant.data.model.AssistantName
import java.text.Normalizer

internal object KwsKeywords {
    private val initials = listOf("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w")
    private const val MAX_PRONUNCIATIONS = 64

    // 只在启动或改名时读取；逐行扫描字典，仅保留昵称所需的几个字。
    fun create(assets: AssetManager, name: String): String? {
        if (!AssistantName.isValid(name)) return null
        val templates = assets.open("kws/keywords.txt").bufferedReader().use { it.readLines() }
        val tokens = assets.open("kws/tokens.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBeforeLast(' ') }.toSet()
        }
        return assets.open("kws/pinyin.txt").bufferedReader().useLines { dictionary ->
            create(name, templates, tokens, dictionary)
        }
    }

    internal fun create(name: String, templates: List<String>, tokens: Set<String>, dictionary: Sequence<String>): String? {
        if (!AssistantName.isValid(name)) return null
        if (name == AssistantName.DEFAULT) return templates.joinToString("/")
        val needed = name.toSet()
        val readings = mutableMapOf<Char, List<String>>()
        for (line in dictionary) {
            if (!line.startsWith("U+")) continue
            val code = line.substringAfter("U+").substringBefore(':').toIntOrNull(16) ?: continue
            if (code > Char.MAX_VALUE.code || code.toChar() !in needed) continue
            readings[code.toChar()] = line.substringAfter(':').substringBefore('#').trim().split(',')
                .mapNotNull { tokenize(it.trim(), tokens) }.distinct()
            if (readings.size == needed.size) break
        }
        var combinations = listOf("")
        for (character in name) {
            val options = readings[character]?.takeIf { it.isNotEmpty() } ?: return null
            if (combinations.size * options.size > MAX_PRONUNCIATIONS) return null
            combinations = combinations.flatMap { prefix -> options.map { "$prefix $it".trim() } }
        }
        return templates.flatMap { line ->
            val parts = line.split(" @", limit = 2)
            if (parts.size != 2) return null
            val label = parts[1].replace(AssistantName.DEFAULT, name)
            combinations.map { pronunciation ->
                "${parts[0].replace("d òu b āo", pronunciation)} @$label"
            }
        }.joinToString("/")
    }

    private fun tokenize(syllable: String, tokens: Set<String>): String? {
        val normalized = Normalizer.normalize(syllable, Normalizer.Form.NFC)
        val initial = initials.firstOrNull { normalized.startsWith(it) }.orEmpty()
        val final = normalized.removePrefix(initial)
        val parts = listOf(initial, final).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() && it.all(tokens::contains) }?.joinToString(" ")
    }
}
