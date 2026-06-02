package com.example.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import java.lang.Math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    SPEAKING,
    THINKING
}

@Composable
fun OrbAnimationView(
    state: OrbState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbInfinite")

    // Slow pulse scale animation (1.0 -> 1.15 -> 1.0) for idle/listening states
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Constant rotation angle animation for rings
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationAngle"
    )

    // Wave speed timing generator (cycles 0 -> 2pi)
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaveOffset"
    )

    // Spinner for Thinking arc loading sequence
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ThinkingSpin"
    )

    // Transitioning colors based on current animation states
    val coreColors = when (state) {
        OrbState.IDLE -> listOf(Color(0xFFB71C1C), Color(0xFF880E4F))
        OrbState.LISTENING -> listOf(Color(0xFFFF1744), Color(0xFFD500F9))
        OrbState.SPEAKING -> listOf(Color(0xFFE040FB), Color(0xFFFF1744))
        OrbState.THINKING -> listOf(Color(0xFF40C4FF), Color(0xFF00B0FF))
    }

    val glowColors = when (state) {
        OrbState.IDLE -> listOf(Color(0xFFB71C1C).copy(alpha = 0.4f), Color.Transparent)
        OrbState.LISTENING -> listOf(Color(0xFFFF1744).copy(alpha = 0.5f), Color.Transparent)
        OrbState.SPEAKING -> listOf(Color(0xFFE040FB).copy(alpha = 0.6f), Color.Transparent)
        OrbState.THINKING -> listOf(Color(0xFF40C4FF).copy(alpha = 0.5f), Color.Transparent)
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.width.coerceAtMost(size.height) * 0.3f
            val currentRadius = if (state == OrbState.IDLE || state == OrbState.LISTENING) {
                baseRadius * pulseScale
            } else {
                baseRadius
            }

            // 1. Radial Background Ambient Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = glowColors,
                    center = center,
                    radius = currentRadius * 1.8f
                ),
                radius = currentRadius * 1.8f,
                center = center
            )

            // 2. Rotating Dash Rings (3 layered orbits)
            val ringCount = 3
            for (i in 0 until ringCount) {
                val ringRadius = currentRadius * (1.15f + i * 0.12f)
                val rotateSpeedMultiplier = if (i % 2 == 0) 1.2f else -0.8f
                
                rotate(degrees = rotationAngle * rotateSpeedMultiplier, pivot = center) {
                    drawCircle(
                        color = coreColors[0].copy(alpha = 0.35f - i * 0.08f),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(
                                    35.dp.toPx() + i * 10,
                                    20.dp.toPx() - i * 5
                                ),
                                phase = waveOffset * 10
                            )
                        )
                    )
                }
            }

            // 3. Amplitude-reactive Undulating Sine Wave Rings
            if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
                val waveCount = 2
                val activeAmp = amplitude.coerceAtLeast(0.05f)
                
                for (w in 0 until waveCount) {
                    val path = Path()
                    val wavePoints = 120
                    val waveRadius = currentRadius * (1.05f + w * 0.08f)
                    val phaseShift = w * (PI / 2).toFloat()
                    val frequency = 4 + w * 2
                    val ampHeight = currentRadius * 0.15f * activeAmp

                    for (p in 0..wavePoints) {
                        val angle = (p.toFloat() / wavePoints) * (2 * PI).toFloat()
                        // Add sine perturbation to base radius
                        val perturbedRadius = waveRadius + sin(angle * frequency + waveOffset + phaseShift) * ampHeight
                        
                        val x = center.x + perturbedRadius * cos(angle).toFloat()
                        val y = center.y + perturbedRadius * sin(angle).toFloat()

                        if (p == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    path.close()

                    drawPath(
                        path = path,
                        color = coreColors[1].copy(alpha = 0.5f - w * 0.15f),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }

            // 4. Thinking Spinning Arcs (loading states)
            if (state == OrbState.THINKING) {
                rotate(degrees = spinAngle, pivot = center) {
                    drawArc(
                        color = Color(0xFF00B0FF),
                        startAngle = 0f,
                        sweepAngle = 100f,
                        useCenter = false,
                        size = Size(currentRadius * 2.4f, currentRadius * 2.4f),
                        topLeft = Offset(center.x - currentRadius * 1.2f, center.y - currentRadius * 1.2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0xFF40C4FF).copy(alpha = 0.5f),
                        startAngle = 180f,
                        sweepAngle = 120f,
                        useCenter = false,
                        size = Size(currentRadius * 2.5f, currentRadius * 2.5f),
                        topLeft = Offset(center.x - currentRadius * 1.25f, center.y - currentRadius * 1.25f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 5. Solid Core Orb Sphere (simulated sphere via 3D radial gradient)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColors[0].copy(alpha = 0.95f), coreColors[1]),
                    center = Offset(center.x - currentRadius * 0.2f, center.y - currentRadius * 0.2f),
                    radius = currentRadius
                ),
                radius = currentRadius,
                center = center
            )

            // 6. Spotlight Highlight Glow (upper left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(center.x - currentRadius * 0.4f, center.y - currentRadius * 0.4f),
                    radius = currentRadius * 0.4f
                ),
                radius = currentRadius * 0.4f,
                center = Offset(center.x - currentRadius * 0.4f, center.y - currentRadius * 0.4f)
            )

            // 7. Orbiting Atmospheric Particles
            if (state == OrbState.SPEAKING || state == OrbState.LISTENING) {
                val particleCount = 12
                val activeAmp = amplitude.coerceAtLeast(0.08f)
                val orbitRadius = currentRadius * 1.45f

                for (p in 0 until particleCount) {
                    val offsetAngle = (p.toFloat() / particleCount) * (2 * PI).toFloat()
                    val speedMultiplier = if (p % 2 == 0) 1f else -1.2f
                    val angle = offsetAngle + (rotationAngle * (PI / 180f).toFloat()) * speedMultiplier
                    
                    // Wave expansion based on amplitude
                    val ext = ampHeightOffset(p, waveOffset, activeAmp * orbitRadius * 0.15f)
                    val r = orbitRadius + ext

                    val px = center.x + r * cos(angle).toFloat()
                    val py = center.y + r * sin(angle).toFloat()

                    drawCircle(
                        color = coreColors[0].copy(alpha = 0.7f),
                        radius = (3.dp.toPx() + (p % 3) * 1.5f),
                        center = Offset(px, py)
                    )
                }
            }
        }
    }
}

private fun ampHeightOffset(index: Int, offset: Float, maxAmplitude: Float): Float {
    return sin(index.toFloat() * 1.5f + offset) * maxAmplitude
}
