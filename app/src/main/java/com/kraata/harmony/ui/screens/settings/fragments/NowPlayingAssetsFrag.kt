package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kraata.harmony.R
import com.kraata.harmony.constants.NowPlayingShardGroupKey
import com.kraata.harmony.data.NowPlayingAssetsDownloader
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.ui.dialog.DefaultDialog
import com.kraata.harmony.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ColumnScope.NowPlayingAssetsFrag() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { NowPlayingAssetsDownloader() }
    val (selectedGroup, onSelectedGroupChange) = rememberPreference(
        NowPlayingShardGroupKey,
        NowPlayingAssetsDownloader.defaultShardGroup(),
    )
    val regionComponents = listOf(
        NowPlayingAssetsDownloader.Component.AR,
        NowPlayingAssetsDownloader.Component.AU,
        NowPlayingAssetsDownloader.Component.BR,
        NowPlayingAssetsDownloader.Component.CA,
        NowPlayingAssetsDownloader.Component.CH,
        NowPlayingAssetsDownloader.Component.DE,
        NowPlayingAssetsDownloader.Component.ES,
        NowPlayingAssetsDownloader.Component.FR,
        NowPlayingAssetsDownloader.Component.GB,
        NowPlayingAssetsDownloader.Component.IE,
        NowPlayingAssetsDownloader.Component.IN,
        NowPlayingAssetsDownloader.Component.IT,
        NowPlayingAssetsDownloader.Component.JP,
        NowPlayingAssetsDownloader.Component.MX,
        NowPlayingAssetsDownloader.Component.NL,
        NowPlayingAssetsDownloader.Component.RU,
        NowPlayingAssetsDownloader.Component.US_XA,
    )
    val displayLocale = LocalConfiguration.current.locales[0]
    val regionNames = regionComponents.associateWith { component ->
        val name = if (component == NowPlayingAssetsDownloader.Component.US_XA) {
            stringResource(R.string.music_recognition_data_us_xa)
        } else if (component == NowPlayingAssetsDownloader.Component.MX) {
            stringResource(R.string.music_recognition_data_mx)
        } else {
            Locale.Builder()
                .setRegion(component.directory.uppercase(Locale.ROOT))
                .build()
                .getDisplayCountry(displayLocale)
        }
        val flag = component.directory.take(2).uppercase(Locale.ROOT)
            .map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }
            .joinToString("")
        "$flag $name"
    }
    val selectedGroups = selectedGroup.split(',')
        .filter { group -> regionComponents.any { it.directory == group } }
        .toSet()
    val selectedComponents = regionComponents.filter { it.directory in selectedGroups }
    val genericErrorMessage = stringResource(R.string.music_recognition_data_error)
    val deleteErrorMessage = stringResource(R.string.music_recognition_data_delete_error)
    var activeComponent by remember { mutableStateOf<NowPlayingAssetsDownloader.Component?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var componentToDelete by remember {
        mutableStateOf<NowPlayingAssetsDownloader.Component?>(null)
    }
    var deletingComponent by remember {
        mutableStateOf<NowPlayingAssetsDownloader.Component?>(null)
    }
    val coreInstalled = downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE)
    val regionInstalled = regionComponents.any { downloader.isInstalled(context, it) }
    val canDeleteCore = coreInstalled && !regionInstalled

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
        val pendingComponents = components.filterNot { downloader.isInstalled(context, it) }
        if (activeComponent != null || deletingComponent != null || pendingComponents.isEmpty()) return
        activeComponent = pendingComponents.first()
        progress = 0
        errorMessage = null
        scope.launch {
            try {
                pendingComponents.forEach { component ->
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

    fun delete(component: NowPlayingAssetsDownloader.Component) {
        if (activeComponent != null || deletingComponent != null) return
        componentToDelete = null
        errorMessage = null
        deletingComponent = component
        scope.launch {
            try {
                downloader.delete(context, component)
            } catch (exception: Exception) {
                errorMessage = exception.message ?: deleteErrorMessage
            } finally {
                deletingComponent = null
            }
        }
    }

    val selectedNames = selectedComponents.joinToString(" + ") {
        regionNames.getValue(it)
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        PreferenceEntry(
            title = {
                Text(stringResource(R.string.music_recognition_data_region))
            },
            description = if (selectedComponents.isEmpty()) {
                stringResource(R.string.music_recognition_data_no_region_selected)
            } else {
                selectedNames
            },
            trailingContent = {
                Icon(
                    imageVector = if (regionMenuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            },
            onClick = { regionMenuExpanded = !regionMenuExpanded },
            isEnabled = activeComponent == null && deletingComponent == null,
        )
        DropdownMenu(
            expanded = regionMenuExpanded,
            onDismissRequest = { regionMenuExpanded = false },
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            regionComponents.forEach { component ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.music_recognition_data_with_size,
                                regionNames.getValue(component),
                                "${component.approximateSizeMb} MB",
                            ),
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = component.directory in selectedGroups,
                                onCheckedChange = null,
                            )
                            if (downloader.isInstalled(context, component)) {
                                IconButton(
                                    onClick = {
                                        regionMenuExpanded = false
                                        componentToDelete = component
                                    },
                                    enabled = activeComponent == null && deletingComponent == null,
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(
                                            R.string.music_recognition_data_delete,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    onClick = { toggleRegion(component) },
                    enabled = activeComponent == null && deletingComponent == null,
                )
            }
        }
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
        isEnabled = activeComponent == null && deletingComponent == null && selectedComponents.isNotEmpty() && !selectedComponentsInstalled,
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
        description = if (coreInstalled) {
            stringResource(R.string.music_recognition_data_installed)
        } else {
            stringResource(R.string.music_recognition_data_not_installed)
        },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
        trailingContent = if (coreInstalled) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.music_recognition_data_installed),
                    )
                    if (canDeleteCore) {
                        IconButton(
                            onClick = {
                                componentToDelete = NowPlayingAssetsDownloader.Component.CORE
                            },
                            enabled = deletingComponent == null,
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(
                                    R.string.music_recognition_data_delete,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        onClick = { download(listOf(NowPlayingAssetsDownloader.Component.CORE)) },
        isEnabled = activeComponent == null && deletingComponent == null && !coreInstalled,
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

    componentToDelete?.let { component ->
        DefaultDialog(
            onDismiss = { componentToDelete = null },
            content = {
                Text(
                    text = stringResource(
                        R.string.music_recognition_data_delete_region_confirm,
                        regionNames[component]
                            ?: stringResource(R.string.music_recognition_data_core),
                    ),
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(onClick = { componentToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { delete(component) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    errorMessage?.let {
        Text(
            text = it,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
