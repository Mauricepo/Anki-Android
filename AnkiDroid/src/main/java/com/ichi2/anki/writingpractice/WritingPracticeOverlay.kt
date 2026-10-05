// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.writingpractice

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.ichi2.anki.AnkiActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One drawn point, in the writing canvas's own pixel coordinates, with its capture time -
 * this is exactly what [Ink.Point] needs.
 */
private data class InkPointSample(
    val x: Float,
    val y: Float,
    val timeMillis: Long,
)

private enum class ModelState { PREPARING, READY, UNAVAILABLE }

/**
 * Shows a full-screen "write the word with your finger" overlay on top of [activity], then
 * calls [onFinished] once the user either successfully reproduces [targetWord] (checked via
 * [InkRecognizer]) or taps "Überspringen" (only offered if the recognition model isn't
 * available). The overlay removes itself before calling [onFinished].
 *
 * This is added dynamically to the activity's content view rather than to a layout XML file, so
 * it works regardless of which screen layout (phone/tablet) the Reviewer is currently using.
 *
 * @param strokeWidthPx stroke width in raw pixels, matching the existing "whiteBoardStrokeWidth"
 *   preference so the drawing feels consistent with the regular Whiteboard feature.
 */
fun showWritingPracticeOverlay(
    activity: AnkiActivity,
    targetWord: String,
    languageTag: String,
    strokeWidthPx: Float,
    onFinished: () -> Unit,
) {
    val root = activity.findViewById<ViewGroup>(android.R.id.content)
    val composeView = ComposeView(activity)
    composeView.layoutParams =
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    fun dismissAndFinish() {
        root.removeView(composeView)
        onFinished()
    }

    composeView.setContent {
        WritingPracticeContent(
            targetWord = targetWord,
            languageTag = languageTag,
            strokeWidthPx = strokeWidthPx,
            onDone = { dismissAndFinish() },
            onSkip = { dismissAndFinish() },
        )
    }
    root.addView(composeView)
}

private fun buildInk(strokes: List<List<InkPointSample>>): Ink {
    val inkBuilder = Ink.builder()
    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val strokeBuilder = Ink.Stroke.builder()
        for (point in stroke) {
            strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timeMillis))
        }
        inkBuilder.addStroke(strokeBuilder.build())
    }
    return inkBuilder.build()
}

@Composable
private fun WritingPracticeContent(
    targetWord: String,
    languageTag: String,
    strokeWidthPx: Float,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var modelState by remember { mutableStateOf(ModelState.PREPARING) }
    var checking by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf(listOf<List<InkPointSample>>()) }
    var currentStroke by remember { mutableStateOf(listOf<InkPointSample>()) }
    var showMistakeHint by remember { mutableStateOf(false) }

    LaunchedEffect(languageTag) {
        modelState = if (InkRecognizer.ensureModelReady(languageTag)) ModelState.READY else ModelState.UNAVAILABLE
    }

    fun clearCanvas() {
        strokes = emptyList()
        currentStroke = emptyList()
    }

    fun submit() {
        if (checking || strokes.isEmpty()) return
        checking = true
        scope.launch {
            val candidates = InkRecognizer.recognize(buildInk(strokes), languageTag)
            checking = false
            if (InkRecognizer.matches(candidates, targetWord)) {
                onDone()
            } else {
                clearCanvas()
                showMistakeHint = true
                delay(1200)
                showMistakeHint = false
            }
        }
    }

    val canDraw = modelState == ModelState.READY && !checking

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xE6101010)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Schreib/male nach:",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                targetWord,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .let { base ->
                            if (canDraw) {
                                base.pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentStroke = listOf(InkPointSample(offset.x, offset.y, System.currentTimeMillis()))
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            currentStroke =
                                                currentStroke +
                                                InkPointSample(change.position.x, change.position.y, System.currentTimeMillis())
                                        },
                                        onDragEnd = {
                                            if (currentStroke.size >= 2) strokes = strokes + listOf(currentStroke)
                                            currentStroke = emptyList()
                                        },
                                    )
                                }
                            } else {
                                base
                            }
                        },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (stroke in strokes + listOf(currentStroke)) {
                        if (stroke.size < 2) continue
                        val path = Path()
                        path.moveTo(stroke.first().x, stroke.first().y)
                        for (point in stroke.drop(1)) path.lineTo(point.x, point.y)
                        drawPath(path, color = Color.Black, style = Stroke(width = strokeWidthPx))
                    }
                }

                when (modelState) {
                    ModelState.PREPARING ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    ModelState.UNAVAILABLE ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Schrifterkennung nicht verfügbar (Internet für den einmaligen " +
                                    "Modell-Download nötig).",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    ModelState.READY -> Unit
                }
            }

            Spacer(Modifier.height(8.dp))
            if (showMistakeHint) {
                Text(
                    "Nicht erkannt - versuch's nochmal.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { clearCanvas() }, enabled = strokes.isNotEmpty()) {
                    Text("Löschen")
                }
                Button(onClick = { submit() }, enabled = canDraw && strokes.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                    } else {
                        Text("Prüfen")
                    }
                }
            }
            if (modelState == ModelState.UNAVAILABLE) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Überspringen")
                }
            }
        }
    }
}
