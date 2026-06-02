package com.example.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformView(
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 20
    val animatedBars = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.05f) } } }

    val infiniteTransition = rememberInfiniteTransition(label = "WaveformIdle")

    // Slow ambient oscillation for idle wave bars when no sound is active
    val ambientOscillation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = Math.PI.toFloat() * 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AmbientOscillation"
    )

    // Smoothly interpolate heights towards target values based on real-time amplitude
    LaunchedEffect(amplitude, ambientOscillation) {
        val targetAmp = amplitude.coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val ratio = if (targetAmp < 0.02f) {
                // Idle state: generate small natural ripple wave using phase offsets
                val offsetPhase = i.toFloat() * 0.35f
                val sinVal = kotlin.math.sin(ambientOscillation + offsetPhase)
                val normSin = (sinVal + 1f) / 2f
                0.04f + normSin * 0.08f
            } else {
                // Active state: map amplitude to random/symmetric peaks
                val symmetricDistType = if (i < barCount / 2) i.toFloat() else (barCount - 1 - i).toFloat()
                val peakFactor = (symmetricDistType / (barCount / 2)) * 0.8f + 0.2f
                val randVariance = (0.7f + Math.random().toFloat() * 0.5f)
                targetAmp * peakFactor * randVariance
            }

            val currentHeight = animatedBars[i]
            // Safe linear interpolation (lerp factor 0.25f)
            animatedBars[i] = currentHeight + (ratio - currentHeight) * 0.25f
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val spacing = 4.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val usableWidth = canvasWidth - totalSpacing
        val barWidth = (usableWidth / barCount).coerceAtLeast(2.dp.toPx())

        for (i in 0 until barCount) {
            val normHeight = animatedBars[i].coerceIn(0.04f, 1.0f)
            val barHeightPx = canvasHeight * normHeight

            val xOffset = i * (barWidth + spacing)
            val yOffset = (canvasHeight - barHeightPx) / 2f

            // Dynamic opacity based on peak bar heights (alphas range 150 to 255)
            val computedAlpha = (150f + (105f * normHeight)).toInt().coerceIn(150, 255)
            val barColor = Color(0xFFFF1744).copy(alpha = computedAlpha / 255f)

            drawRoundRect(
                color = barColor,
                topLeft = Offset(xOffset, yOffset),
                size = Size(barWidth, barHeightPx),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
