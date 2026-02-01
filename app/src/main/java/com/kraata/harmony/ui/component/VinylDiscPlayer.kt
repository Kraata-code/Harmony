/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.kraata.harmony.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * Componente que muestra una imagen como disco de vinilo realista con efecto giratorio.
 * Replica el aspecto de un vinilo real con surcos, brillo y la carátula del álbum en el centro.
 *
 * @param imageModel Modelo de la imagen (URL, resource, etc.)
 * @param progress Progreso de reproducción (0f a 1f)
 * @param rotation Rotación actual en grados
 * @param showError Si debe mostrar el overlay de error
 * @param showLoading Si debe mostrar el overlay de carga
 * @param modifier Modificador para el contenedor
 * @param size Tamaño total del componente
 */
@Composable
fun VinylDiscPlayer(
    imageModel: Any?,
    progress: Float,
    rotation: Float,
    showError: Boolean,
    showLoading: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Anillo de progreso (no rota)
        CircularProgressThumbnail(
            progress = progress,
            showError = showError,
            showLoading = showLoading,
            size = size,
            strokeWidth = 3.dp,
            modifier = Modifier.fillMaxSize()
        )

        // Capa giratoria (vinilo + imagen)
        Box(
            modifier = Modifier
                .size(size - 10.dp) // Espacio para el anillo de progreso
                .graphicsLayer(rotationZ = rotation),
            contentAlignment = Alignment.Center
        ) {
            // Disco de vinilo realista
            RealisticVinylDisc(
                modifier = Modifier.fillMaxSize()
            )

            // Imagen del álbum (carátula en el centro)
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                modifier = Modifier
                    .size((size - 10.dp) * 0.65f) // 65% del disco para la carátula
                    .clip(CircleShape)
            )
        }
    }
}

/**
 * Dibuja un disco de vinilo realista con surcos, gradientes y efectos de brillo.
 */
@Composable
private fun RealisticVinylDisc(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val maxRadius = minOf(size.width, size.height) / 2

        // Fondo del disco (más claro para destacar sobre fondo negro)
        val vinylGradient = Brush.radialGradient(
            colors = listOf(
                Color(0xFF000000), // Centro
                Color(0xFF1F1F1F), // Medio
                Color(0xFF0B0B0B)  // Borde
            ),
            center = Offset(centerX, centerY),
            radius = maxRadius
        )

        drawCircle(
            brush = vinylGradient,
            radius = maxRadius,
            center = Offset(centerX, centerY)
        )

        // Surcos del vinilo (más visibles)
        val grooveCount = 40
        val grooveStartRadius = maxRadius * 0.68f

        for (i in 0 until grooveCount) {
            val progress = i.toFloat() / grooveCount
            val grooveRadius =
                grooveStartRadius + (maxRadius - grooveStartRadius) * progress

            val grooveColor = if (i % 2 == 0) {
                Color(0xFF2A2A2A)
            } else {
                Color(0xFF1A1A1A)
            }

            drawCircle(
                color = grooveColor,
                radius = grooveRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.1f)
            )
        }

        // Etiqueta central
        val labelRadius = maxRadius * 0.65f

        val labelGradient = Brush.radialGradient(
            colors = listOf(
                Color(0xFF000000),
                Color(0xFF2C2C2C)
            ),
            center = Offset(centerX, centerY),
            radius = labelRadius
        )

        drawCircle(
            brush = labelGradient,
            radius = labelRadius,
            center = Offset(centerX, centerY)
        )

        // Borde de la etiqueta
        drawCircle(
            color = Color(0xFF5A5A5A),
            radius = labelRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.5f)
        )

        // Agujero central
        val holeRadius = maxRadius * 0.12f

        drawCircle(
            color = Color(0xFF111111),
            radius = holeRadius,
            center = Offset(centerX, centerY)
        )

        drawCircle(
            color = Color(0xFF2A2A2A),
            radius = holeRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.2f)
        )

        // Brillo principal (luz superior izquierda)
        val highlightOffset = Offset(
            centerX - maxRadius * 0.3f,
            centerY - maxRadius * 0.3f
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = highlightOffset,
                radius = maxRadius * 0.5f
            ),
            radius = maxRadius * 0.5f,
            center = highlightOffset
        )

        // Líneas de reflexión
        val reflectionCount = 4
        for (i in 0 until reflectionCount) {
            val angle = (i * 90f + 45f) * (Math.PI / 180f).toFloat()
            val startRadius = labelRadius + 6f
            val endRadius = maxRadius - 6f

            val startX = centerX + cos(angle) * startRadius
            val startY = centerY + sin(angle) * startRadius
            val endX = centerX + cos(angle) * endRadius
            val endY = centerY + sin(angle) * endRadius

            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 1.2f,
                cap = StrokeCap.Round
            )
        }

        // Sombra interna del borde
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = maxRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 3f)
        )
    }

}