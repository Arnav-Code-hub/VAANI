package com.bithead.shelter.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.icu.text.Transliterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.coroutines.CoroutineContext

/** Noncritical generated text only. Fixed warnings and SMS never pass through this model. */
object OfflineIndicTranslator {
    private const val ASSETS = "models/indictrans2"
    private val lock = Mutex()
    private val files = linkedMapOf(
        "encoder_model.onnx" to "87c3d6c6fbc48d9d7081927f3bd59915d5443a06cb63460e0d3705bae74de5a9",
        "encoder_model.onnx.data" to "71a4d119514411a3cb3f9d76d2d88d5c4eac7c8e907397d62d5b29e2468a0c16",
        "decoder_model.onnx" to "58c0fafb7ba0f343acba8643a386fab08143d49ef24472cefaecc87080f44e75",
        "decoder_with_past_model.onnx" to "a2e9a87ff7563f5fc87fe8102188be37fb66771cf30970e649ca67be7f895497",
        "decoder_shared.onnx.data" to "902314746ac629ae2ff584709c03eecdb0b3f7be4fb70ce0ff1bfbe66f14e574"
    )

    fun targetTag(language: String): String? = when (language.substringBefore('-')) {
        "hi" -> "hin_Deva"
        "bn" -> "ben_Beng"
        "mr" -> "mar_Deva"
        "ta" -> "tam_Taml"
        else -> null
    }

    suspend fun translateBatch(context: Context, source: List<String>, language: String): List<String> {
        val target = targetTag(language) ?: return source
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val memory = context.getSystemService(ActivityManager::class.java)
                check(memory?.isLowRamDevice != true) {
                    "This phone has insufficient memory for offline summary translation"
                }
                val path = prepareModel(context)
                val tokenizer = BpeTokenizer(JSONObject(context.assets.open("$ASSETS/tokenizer_src.json")
                    .bufferedReader().use { it.readText() }))
                val targetTokens = TargetTokens(JSONObject(context.assets.open("$ASSETS/tokenizer_tgt.json")
                    .bufferedReader().use { it.readText() }))
                val environment = OrtEnvironment.getEnvironment()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    environment.createSession(File(path, "encoder_model.onnx").absolutePath, options).use { encoder ->
                        environment.createSession(File(path, "decoder_model.onnx").absolutePath, options).use { decoder ->
                            environment.createSession(File(path, "decoder_with_past_model.onnx").absolutePath, options).use { past ->
                                val coroutine = currentCoroutineContext()
                                source.map {
                                    coroutine.ensureActive()
                                    translateOne(environment, encoder, decoder, past, tokenizer, targetTokens, it, target, coroutine)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prepareModel(context: Context): File {
        val dir = File(context.filesDir, "offline_translation_v1").apply { mkdirs() }
        if (files.keys.any { !File(dir, it).isFile }) {
            require(dir.usableSpace > 400L * 1024L * 1024L) {
                "Offline translation needs 400 MiB of free storage"
            }
        }
        files.forEach { (name, expectedHash) ->
            val dest = File(dir, name)
            val marker = File(dir, "$name.verified")
            if (dest.isFile && marker.takeIf { it.isFile }?.readText() == expectedHash) return@forEach
            if (dest.isFile && sha256(dest) == expectedHash) {
                marker.writeText(expectedHash)
                return@forEach
            }
            val temp = File(dir, "$name.tmp")
            context.assets.open("$ASSETS/$name").use { input -> temp.outputStream().use(input::copyTo) }
            check(sha256(temp) == expectedHash) { "Bundled translation model checksum mismatch: $name" }
            check(temp.renameTo(dest)) { "Could not install translation model: $name" }
            marker.writeText(expectedHash)
        }
        return dir
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun translateOne(env: OrtEnvironment, encoder: OrtSession, decoder: OrtSession,
        past: OrtSession, tokenizer: BpeTokenizer, targetTokens: TargetTokens,
        text: String, target: String, coroutine: CoroutineContext): String {
        val ids = tokenizer.encode(text, target)
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { mask ->
                encoder.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { encoded ->
                    val hidden = encoded[0] as OnnxTensor
                    val generated = mutableListOf(2)
                    var previous: OrtSession.Result? = null
                    try {
                        repeat(96) { step ->
                            coroutine.ensureActive()
                            val nextInput = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(generated.last().toLong())), longArrayOf(1, 1))
                            val result = try {
                                if (step == 0) decoder.run(mapOf(
                                    "input_ids" to nextInput, "encoder_hidden_states" to hidden,
                                    "encoder_attention_mask" to mask))
                                else {
                                    val feed = mutableMapOf<String, OnnxTensor>(
                                        "input_ids" to nextInput, "encoder_attention_mask" to mask)
                                    val old = checkNotNull(previous)
                                    val layers = (old.size() - 1) / 4
                                    for (i in 0 until layers) {
                                        val base = 1 + i * 4
                                        feed["past_key_values.$i.decoder.key"] = old[base] as OnnxTensor
                                        feed["past_key_values.$i.decoder.value"] = old[base + 1] as OnnxTensor
                                        feed["past_key_values.$i.encoder.key"] = old[base + 2] as OnnxTensor
                                        feed["past_key_values.$i.encoder.value"] = old[base + 3] as OnnxTensor
                                    }
                                    past.run(feed)
                                }
                            } finally { nextInput.close() }
                            previous?.close()
                            previous = result
                            val logits = (result[0] as OnnxTensor).floatBuffer
                            var winner = 0
                            var maximum = Float.NEGATIVE_INFINITY
                            for (i in 0 until logits.limit()) {
                                val value = logits.get(i)
                                if (value > maximum) { maximum = value; winner = i }
                            }
                            generated += winner
                            if (winner == 2) return targetTokens.decode(generated, target)
                        }
                    } finally { previous?.close() }
                    return targetTokens.decode(generated, target)
                }
            }
        }
    }

    private class BpeTokenizer(json: JSONObject) {
        private val vocab = json.getJSONObject("model").getJSONObject("vocab").let { obj ->
            obj.keys().asSequence().associateWith(obj::getInt)
        }
        private val ranks = json.getJSONObject("model").getJSONArray("merges").let { merges ->
            buildMap {
                for (i in 0 until merges.length()) {
                    val pair = merges.getJSONArray(i)
                    put(pair.getString(0) to pair.getString(1), i)
                }
            }
        }

        fun encode(text: String, target: String): LongArray {
            val ids = mutableListOf(vocab.getValue("eng_Latn").toLong(), vocab.getValue(target).toLong())
            val cleaned = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
                .replace(Regex("\\s+"), " ")
            val words = cleaned.split(' ').filter(String::isNotBlank)
            words.forEach { word ->
                val pieces = mutableListOf("▁")
                word.codePoints().forEach { pieces += String(Character.toChars(it)) }
                while (pieces.size > 1) {
                    val best = (0 until pieces.lastIndex).minByOrNull {
                        ranks[pieces[it] to pieces[it + 1]] ?: Int.MAX_VALUE
                    } ?: break
                    if (ranks[pieces[best] to pieces[best + 1]] == null) break
                    pieces[best] += pieces.removeAt(best + 1)
                }
                pieces.forEach { piece ->
                    ids += vocab[piece]?.takeIf { it < 32_322 }?.toLong() ?: 3L
                }
            }
            ids += 2L
            return ids.toLongArray()
        }
    }

    private class TargetTokens(json: JSONObject) {
        private val tokens = json.getJSONObject("model").getJSONObject("vocab").let { obj ->
            Array<String?>(130_494) { null }.also { result ->
                obj.keys().forEach { token ->
                    val id = obj.getInt(token)
                    if (id in result.indices) result[id] = token
                }
            }
        }

        fun decode(ids: List<Int>, target: String): String {
            val raw = ids.asSequence().filter { it in 4 until 122_672 }
                .mapNotNull { tokens.getOrNull(it) }.joinToString("")
                .replace('▁', ' ').trim()
            return when (target) {
                "ben_Beng" -> Transliterator.getInstance("Devanagari-Bengali").transliterate(raw)
                "tam_Taml" -> Transliterator.getInstance("Devanagari-Tamil").transliterate(raw)
                else -> raw
            }
        }
    }
}
