/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.extensions

fun <T> List<T>.reversed(reversed: Boolean) = if (reversed) asReversed() else this

fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int): MutableList<T> {
    add(toIndex, removeAt(fromIndex))
    return this
}
