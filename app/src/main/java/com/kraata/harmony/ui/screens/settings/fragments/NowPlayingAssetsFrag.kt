package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kraata.harmony.R
import com.kraata.harmony.constants.NowPlayingShardGroupKey
import com.kraata.harmony.data.NowPlayingAssetsDownloader
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ColumnScope.NowPlayingAssetsFrag(wizardMode: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { NowPlayingAssetsDownloader() }
    val (selectedGroup, onSelectedGroupChange) = rememberPreference(
        NowPlayingShardGroupKey,
        NowPlayingAssetsDownloader.defaultShardGroup(),
    )
    val regionComponents = listOf(
        NowPlayingAssetsDownloader.Component.MX,
        NowPlayingAssetsDownloader.Component.US_XA,
    )
    val selectedGroups = selectedGroup.split(',')
        .filter { group -> regionComponents.any { it.directory == group } }
        .toSet()
    val selectedComponents = regionComponents.filter { it.directory in selectedGroups }
    val genericErrorMessage = stringResource(R.string.music_recognition_data_error)
    var activeComponent by remember { mutableStateOf<NowPlayingAssetsDownloader.Component?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    fun toggleRegion(component: NowPlayingAssetsDownloader.Component) {
        val updatedGroups = if (component.directory in selectedGroups) {
            selectedGroups - component.directory
        } else {
            selectedGroups + component.directory
        }
        onSelectedGroupChange(
            regionComponents
                .filter { it.directory in updatedGroups }
                .joinToString(",") { it.directory },
        )
    }

    fun download(components: List<NowPlayingAssetsDownloader.Component>) {
        if (activeComponent != null || components.isEmpty()) return
        activeComponent = components.first()
        progress = 0
        errorMessage = null
        scope.launch {
            try {
                components.forEach { component ->
                    activeComponent = component
                    progress = 0
                    downloader.download(context, component).collect { progress = it }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                errorMessage = exception.message ?: genericErrorMessage
            } finally {
                activeComponent = null
            }
        }
    }

    Text(
        text = stringResource(R.string.music_recognition_data_region),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    regionComponents.forEach { component ->
        val name = if (component == NowPlayingAssetsDownloader.Component.MX) {
            "🇲🇽 ${stringResource(R.string.music_recognition_data_mx)}"
        } else {
            "🇺🇸 ${stringResource(R.string.music_recognition_data_us_xa)}"
        }
        PreferenceEntry(
            title = {
                Text(
                    stringResource(
                        R.string.music_recognition_data_with_size,
                        name,
                        "${component.approximateSizeMb} MB",
                    ),
                )
            },
            description = if (component.directory in selectedGroups) {
                stringResource(R.string.music_recognition_data_selected)
            } else {
                stringResource(R.string.music_recognition_data_not_selected)
            },
            trailingContent = {
                Checkbox(
                    checked = component.directory in selectedGroups,
                    onCheckedChange = null,
                )
            },
            onClick = { toggleRegion(component) },
            isEnabled = activeComponent == null,
        )
    }

    PreferenceEntry(
        title = {
            Text(
                stringResource(
                    R.string.music_recognition_data_with_size,
                    stringResource(R.string.music_recognition_data_core),
                    "${NowPlayingAssetsDownloader.Component.CORE.approximateSizeMb} MB",
                ),
            )
        },
        description = if (downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE)) {
            stringResource(R.string.music_recognition_data_installed)
        } else {
            stringResource(R.string.music_recognition_data_not_installed)
        },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
        trailingContent = if (downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE)) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.music_recognition_data_installed),
                )
            }
        } else {
            null
        },
        onClick = { download(listOf(NowPlayingAssetsDownloader.Component.CORE)) },
        isEnabled = activeComponent == null,
        content = {
            if (activeComponent == NowPlayingAssetsDownloader.Component.CORE) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
    )

    if (wizardMode) {
        val mxName = "🇲🇽 ${stringResource(R.string.music_recognition_data_mx)}"
        val usXaName = "🇺🇸 ${stringResource(R.string.music_recognition_data_us_xa)}"
        val selectedNames = selectedComponents.joinToString(" + ") {
            if (it == NowPlayingAssetsDownloader.Component.MX) mxName else usXaName
        }
        val selectedComponentsInstalled = selectedComponents.isNotEmpty() && selectedComponents.all {
            downloader.isInstalled(context, it)
        }
        PreferenceEntry(
            title = {
                Text(
                    if (selectedComponents.isEmpty()) {
                        stringResource(R.string.music_recognition_data_no_region_selected)
                    } else {
                        stringResource(
                            R.string.music_recognition_data_with_size,
                            selectedNames,
                            NowPlayingAssetsDownloader.totalApproximateSize(selectedComponents),
                        )
                    },
                )
            },
            description = if (selectedComponents.isEmpty()) {
                stringResource(R.string.music_recognition_data_no_region_selected)
            } else {
                stringResource(R.string.music_recognition_data_region_download)
            },
            icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
            trailingContent = if (selectedComponentsInstalled) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.music_recognition_data_installed),
                    )
                }
            } else {
                null
            },
            onClick = { download(selectedComponents) },
            isEnabled = activeComponent == null && selectedComponents.isNotEmpty(),
            content = {
                if (activeComponent?.let { it in selectedComponents } == true) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
        )
    } else {
        regionComponents.forEach { component ->
            val name = if (component == NowPlayingAssetsDownloader.Component.MX) {
                "🇲🇽 ${stringResource(R.string.music_recognition_data_mx)}"
            } else {
                "🇺🇸 ${stringResource(R.string.music_recognition_data_us_xa)}"
            }
            PreferenceEntry(
                title = {
                    Text(
                        stringResource(
                            R.string.music_recognition_data_with_size,
                            name,
                            "${component.approximateSizeMb} MB",
                        ),
                    )
                },
                description = if (downloader.isInstalled(context, component)) {
                    stringResource(R.string.music_recognition_data_installed)
                } else {
                    stringResource(R.string.music_recognition_data_not_installed)
                },
                icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
                trailingContent = if (downloader.isInstalled(context, component)) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.music_recognition_data_installed),
                        )
                    }
                } else {
                    null
                },
                onClick = { download(listOf(component)) },
                isEnabled = activeComponent == null,
                content = {
                    if (activeComponent == component) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                },
            )
        }
    }

    errorMessage?.let {
        Text(
            text = it,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
