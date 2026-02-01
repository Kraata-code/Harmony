/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.kraata.harmony.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kraata.harmony.constants.ThumbnailCornerRadius

/**
 * Componente reutilizable que muestra un anillo de progreso circular alrededor de un contenedor.
 * Diseñado para envolver thumbnails o imágenes con una barra de progreso en el perímetro.
 *
 * @param progress Progreso actual (0f a 1f)
 * @param showError Si debe mostrar el icono de error en el overlay
 * @param showLoading Si debe mostrar el indicador de carga en el overlay
 * @param modifier Modificador para personalizar el componente
 * @param size Tamaño total del componente (incluye el anillo de progreso)
 * @param strokeWidth Ancho de la barra de progreso
 * @param progressColor Color de la barra de progreso
 * @param backgroundProgressColor Color del fondo de la barra de progreso
 */
@Composable
fun CircularProgressThumbnail(
    progress: Float,
    showError: Boolean,
    showLoading: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    strokeWidth: Dp = 4.dp,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    backgroundProgressColor: Color = Color.Gray
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Capa de progreso circular (dibuja en el perímetro)
        CircularProgressRing(
            progress = progress,
            strokeWidth = strokeWidth,
            progressColor = progressColor,
            backgroundColor = backgroundProgressColor,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay de estados (error/loading)
        StateOverlay(
            showError = showError,
            showLoading = showLoading
        )
    }
}

/**
 * Dibuja el anillo de progreso circular en el perímetro del contenedor.
 * El anillo se dibuja desde la parte superior (12 en punto) y avanza en sentido horario.
 */
@Composable
private fun CircularProgressRing(
    progress: Float,
    strokeWidth: Dp,
    progressColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = -90f // Inicia desde la parte superior (12 en punto)
        }
    ) {
        val strokePx = strokeWidth.toPx()
        // Radio ajustado para que el anillo se dibuje dentro del contenedor
        val radius = (minOf(size.width, size.height) / 2) - (strokePx / 2)
        val center = Offset(size.width / 2, size.height / 2)

        // Tamaño del área donde se dibuja el arco
        val arcSize = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)

        // Fondo del anillo (círculo completo gris)
        drawCircle(
            color = backgroundColor,
            radius = radius,
            center = center,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round
            )
        )

        // Progreso actual (arco coloreado)
        if (progress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = 0f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

/**
 * Muestra overlay de estados (error o carga).
 * Se superpone al contenido cuando hay un error o está cargando.
 */
@Composable
private fun StateOverlay(
    showError: Boolean,
    showLoading: Boolean
) {
    val showOverlay = remember(showError, showLoading) {
        showError || showLoading
    }

    AnimatedVisibility(
        visible = showOverlay,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(ThumbnailCornerRadius)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                showLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
                showError -> {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}