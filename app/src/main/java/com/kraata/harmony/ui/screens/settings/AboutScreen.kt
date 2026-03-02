/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.BuildConfig
import com.kraata.harmony.R
import com.kraata.harmony.constants.ENABLE_FFMETADATAEX
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.ui.component.ColumnWithContentPadding
import com.kraata.harmony.ui.component.ContributorCard
import com.kraata.harmony.ui.component.ContributorInfo
import com.kraata.harmony.ui.component.ContributorType.CUSTOM
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.component.button.IconLabelButton
import com.kraata.harmony.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    val showDebugInfo = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "userdebug"

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground, BlendMode.SrcIn),
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                .clickable { }
        )

        Row(
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "Harmony",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(Modifier.width(4.dp))

                if (showDebugInfo) {
                    Spacer(Modifier.width(4.dp))

                    Text(
                        text = BuildConfig.BUILD_TYPE.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.secondary,
                                shape = CircleShape
                            )
                            .padding(
                                horizontal = 6.dp,
                                vertical = 2.dp
                            )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Fork de OuterTune desarrollado por",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Jorge Natanael Castolo Gonzalez",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            IconLabelButton(
                text = "GitHub",
                painter = painterResource(R.drawable.github),
                onClick = { uriHandler.openUri("https://github.com/Kraata-code/Harmony") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )

 /*           IconLabelButton(
                text = stringResource(R.string.wiki),
                icon = Icons.Outlined.Info,
                onClick = { uriHandler.openUri("https://github.com/Kraata-code/Harmony/wiki") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )*/
        }

        Spacer(Modifier.height(96.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.attribution_title)) },
                    onClick = {
                        navController.navigate("settings/about/attribution")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.oss_licenses_title)) },
                    onClick = {
                        navController.navigate("settings/about/oss_licenses")
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
//          Esto conecta directo con el git pero no se si implementarlo o no
//            ElevatedCard(
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                PreferenceEntry(
//                    title = { Text(stringResource(R.string.help_bug_report_action)) },
//                    onClick = {
//                        uriHandler.openUri("https://github.com/Kraata-code/Harmony/issues")
//                    }
//                )
//                PreferenceEntry(
//                    title = { Text(stringResource(R.string.help_support_forum)) },
//                    onClick = {
//                        uriHandler.openUri("https://github.com/Kraata-code/Harmony/discussions")
//                    }
//                )
//                PreferenceEntry(
//                    title = { Text(stringResource(R.string.help_contact_email_inquiries)) },
//                    onClick = {
//                        val clipData = ClipData.newPlainText(
//                            context.getString(R.string.app_name),
//                            AnnotatedString("outertune@protonmail.com")
//                        )
//                        clipboardManager.nativeClipboard.setPrimaryClip(clipData)
//                    }
//                )
//            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (ENABLE_FFMETADATAEX) {
                ContributorCard(
                    contributor = ContributorInfo(
                        name = "FFmpeg",
                        description = stringResource(R.string.ffmpeg_lgpl),
                        type = listOf(CUSTOM),
                        url = "https://github.com/OuterTune/ffMetadataEx/blob/main/Modules.md"
                    )
                )
            }
        }

    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
