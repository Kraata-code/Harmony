package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
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
import com.kraata.harmony.ui.component.ListPreference
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ColumnScope.NowPlayingAssetsFrag() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { NowPlayingAssetsDownloader() }
    val (selectedGroup, onSelectedGroupChange) = rememberPreference(
        NowPlayingShardGroupKey,
        NowPlayingAssetsDownloader.defaultShardGroup(),
    )
    val genericErrorMessage = stringResource(R.string.music_recognition_data_error)
    val selectedComponent = NowPlayingAssetsDownloader.Component.entries.firstOrNull {
        it.directory == selectedGroup
    } ?: NowPlayingAssetsDownloader.Component.US_XA
    var activeComponent by remember { mutableStateOf<NowPlayingAssetsDownloader.Component?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun download(component: NowPlayingAssetsDownloader.Component) {
        if (activeComponent != null) return
        activeComponent = component
        progress = 0
        errorMessage = null
        scope.launch {
            try {
                downloader.download(context, component).collect { progress = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                errorMessage = exception.message ?: genericErrorMessage
            } finally {
                activeComponent = null
            }
        }
    }

    ListPreference(
        title = { Text(stringResource(R.string.music_recognition_data_region)) },
        selectedValue = selectedGroup,
        values = listOf(
            NowPlayingAssetsDownloader.Component.MX.directory,
            NowPlayingAssetsDownloader.Component.US_XA.directory,
        ),
        valueText = { group ->
            val component = if (group == NowPlayingAssetsDownloader.Component.MX.directory) {
                NowPlayingAssetsDownloader.Component.MX
            } else {
                NowPlayingAssetsDownloader.Component.US_XA
            }
            val name = if (component == NowPlayingAssetsDownloader.Component.MX) {
                stringResource(R.string.music_recognition_data_mx)
            } else {
                stringResource(R.string.music_recognition_data_us_xa)
            }
            stringResource(R.string.music_recognition_data_with_size, name, component.approximateSize)
        },
        onValueSelected = onSelectedGroupChange,
        isEnabled = activeComponent == null,
    )

    PreferenceEntry(
        title = {
            Text(
                stringResource(
                    R.string.music_recognition_data_with_size,
                    stringResource(R.string.music_recognition_data_core),
                    NowPlayingAssetsDownloader.Component.CORE.approximateSize,
                ),
            )
        },
        description = if (downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE)) {
            stringResource(R.string.music_recognition_data_installed)
        } else {
            stringResource(R.string.music_recognition_data_not_installed)
        },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
        onClick = { download(NowPlayingAssetsDownloader.Component.CORE) },
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

    PreferenceEntry(
        title = { Text(stringResource(R.string.music_recognition_data_region_download)) },
        description = if (downloader.isInstalled(context, selectedComponent)) {
            stringResource(R.string.music_recognition_data_installed)
        } else {
            stringResource(R.string.music_recognition_data_not_installed)
        },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.Download, null) },
        onClick = { download(selectedComponent) },
        isEnabled = activeComponent == null,
        content = {
            if (activeComponent == selectedComponent) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
    )

    errorMessage?.let {
        Text(
            text = it,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
