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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.R
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.ui.component.ColumnWithContentPadding
import com.kraata.harmony.ui.component.ContributorCard
import com.kraata.harmony.ui.component.ContributorInfo
import com.kraata.harmony.ui.component.ContributorType.CUSTOM
import com.kraata.harmony.ui.component.ContributorType.LEAD_DEVELOPER
import com.kraata.harmony.ui.component.ContributorType.MAINTAINER
import com.kraata.harmony.ui.component.ContributorType.ICON_DESIGNER
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            maintainers.map {
                ContributorCard(it)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))



        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            OuterTune.map {
                ContributorCard(
                    contributor = it,
                    descriptionColour = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            contributors.map {
                ContributorCard(
                    contributor = it,
                    descriptionColour = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.attribution_title)) },
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

val maintainers = mutableListOf(
    ContributorInfo(
        name = "Jorge Natanael Castolo Gonzalez",
        alias = "Kraata",
        type = listOf(LEAD_DEVELOPER),
        description = "Un estudiante que inteta triunfar",
        url = "https://github.com/Kraata-code"
    ),
    ContributorInfo(
        name = "Erick Giovanni Villegas Cabrera",
        type = listOf(ICON_DESIGNER),
    ),
)
val OuterTune = mutableListOf(
    ContributorInfo(
        name = "Davide Garberi",
        alias = "DD3Boh",
        type = listOf(LEAD_DEVELOPER, CUSTOM),
        description = "OuterTune creator",
        url = "https://github.com/DD3Boh"
    ),
    ContributorInfo(
        name = "Michael Zh",
        alias = "mikooomich",
        type = listOf(LEAD_DEVELOPER, MAINTAINER, CUSTOM),
        description = "OuterTune creator and Maintainer",
        url = "https://github.com/mikooomich"
    ),
)

val contributors = mutableListOf(
    ContributorInfo(
        name = "Zion Huang",
        alias = "z-huang",
        type = listOf(CUSTOM),
        description = "InnerTune creator",
        url = "https://github.com/z-huang"
    ),
)

//val translators = mutableListOf(
//)
