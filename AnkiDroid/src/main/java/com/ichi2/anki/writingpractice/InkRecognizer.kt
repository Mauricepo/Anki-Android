// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.writingpractice

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Thin wrapper around Google ML Kit's Digital Ink Recognition.
 *
 * Added for a personal fork's "write the word" practice step (see [WritingPracticeOverlay]),
 * triggered after answering a card with "Again"/"Hard": the user re-draws the word they just
 * saw on the back of the card with their finger, and this checks whether what they drew matches.
 *
 * NOTE on package names: as of version 19.0.0 the actual classes in
 * `com.google.mlkit:digital-ink-recognition` live under
 * `com.google.mlkit.vision.digitalink.recognition.*`, NOT `com.google.mlkit.vision.digitalink.*`
 * as some (outdated) documentation suggests - verified against the downloaded AAR's classes.jar.
 */
object InkRecognizer {
    private var cachedModel: DigitalInkRecognitionModel? = null
    private var cachedLanguageTag: String? = null

    private fun modelFor(languageTag: String): DigitalInkRecognitionModel? {
        val existing = cachedModel
        if (existing != null && cachedLanguageTag == languageTag) return existing
        val identifier =
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
                ?: run {
                    Timber.w("InkRecognizer: no digital ink model for language tag '%s'", languageTag)
                    return null
                }
        return DigitalInkRecognitionModel
            .builder(identifier)
            .build()
            .also {
                cachedModel = it
                cachedLanguageTag = languageTag
            }
    }

    suspend fun isModelDownloaded(languageTag: String): Boolean {
        val model = modelFor(languageTag) ?: return false
        return RemoteModelManager.getInstance().isModelDownloaded(model).await()
    }

    /** Downloads the recognition model for [languageTag] if needed. Requires network access once. */
    suspend fun ensureModelReady(languageTag: String): Boolean {
        val model = modelFor(languageTag) ?: return false
        if (RemoteModelManager.getInstance().isModelDownloaded(model).await()) return true
        return try {
            RemoteModelManager
                .getInstance()
                .download(model, DownloadConditions.Builder().build())
                .await()
            true
        } catch (e: Exception) {
            Timber.w(e, "InkRecognizer: failed to download model for '%s'", languageTag)
            false
        }
    }

    /** Returns the recognized text candidates for [ink], best guess first. */
    suspend fun recognize(
        ink: Ink,
        languageTag: String,
    ): List<String> {
        val model = modelFor(languageTag) ?: return emptyList()
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        return try {
            recognizer.recognize(ink).await().candidates.map { it.text }
        } catch (e: Exception) {
            Timber.w(e, "InkRecognizer: recognition failed")
            emptyList()
        } finally {
            recognizer.close()
        }
    }

    private fun normalize(text: String): String = text.trim().replace("　", "").replace(" ", "")

    /** Whether any of [candidates] (as returned by [recognize]) matches [targetWord]. */
    fun matches(
        candidates: List<String>,
        targetWord: String,
    ): Boolean {
        val target = normalize(targetWord)
        if (target.isEmpty()) return false
        return candidates.any { normalize(it) == target }
    }
}
