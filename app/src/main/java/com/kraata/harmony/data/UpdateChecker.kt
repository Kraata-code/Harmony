package com.kraata.harmony.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.kraata.harmony.utils.compareVersion
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"
private const val DEFAULT_UPDATE_URL = "https://github.com/Kraata-code/Harmony/releases/latest/download/app-release.apk"

/**
 * Gestiona la verificación y descarga de actualizaciones de la aplicación.
 * 
 * Implementa el patrón Repository para separar la lógica de red del resto de la aplicación.
 */
class UpdateChecker(
    private val apkUrl: String = DEFAULT_UPDATE_URL,
    private val client: OkHttpClient = createDefaultClient()
) {
    
    companion object {
        private fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Descarga el APK desde la URL configurada.
     * 
     * @param context Contexto de Android necesario para acceso al sistema de archivos
     * @return Flow que emite el progreso de descarga y el estado final
     */
    fun downloadUpdate(context: Context): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0))
        
        try {
            val apkFile = File(context.cacheDir, "app-update.apk")
            
            // Limpiar archivo anterior si existe
            if (apkFile.exists()) {
                apkFile.delete()
            }
            
            val request = Request.Builder()
                .url(apkUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("Error al descargar: código ${response.code}")
                }
                
                val body = response.body ?: throw UpdateException("Respuesta vacía del servidor")
                val contentLength = body.contentLength()
                
                if (contentLength <= 0) {
                    throw UpdateException("Tamaño de archivo inválido")
                }
                
                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            val progress = (totalBytesRead * 100 / contentLength).toInt()
                            emit(DownloadState.Downloading(progress))
                        }
                    }
                }
                
                emit(DownloadState.Downloaded(apkFile))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la descarga", e)
            emit(DownloadState.Error(UpdateException("Error al descargar actualización", e)))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Inicia la instalación del APK descargado.
     * 
     * Requiere que la aplicación tenga el permiso REQUEST_INSTALL_PACKAGES
     * y un FileProvider configurado en el AndroidManifest.xml
     * 
     * @param context Contexto de Android
     * @param apkFile Archivo APK a instalar
     */
    fun installUpdate(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile
            )
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al instalar actualización", e)
            throw UpdateException("Error al iniciar instalación", e)
        }
    }

    /**
     * Comprueba si hay una nueva release en GitHub para el repo derivado de `apkUrl`.
     * Si `apkUrl` apunta a GitHub, consulta `https://api.github.com/repos/{owner}/{repo}/releases/latest`.
     * Emite UpdateCheckState.Loading, UpdateAvailable, UpToDate o Error.
     */
    fun checkForUpdates(context: Context, currentVersionName: String = com.kraata.harmony.BuildConfig.VERSION_NAME): Flow<UpdateCheckState> = flow {
        emit(UpdateCheckState.Loading)
        try {
            val uri = Uri.parse(apkUrl)
            val host = uri.host ?: ""
            if (!host.contains("github.com")) {
                emit(UpdateCheckState.Error(UpdateException("Non-GitHub URL")))
                return@flow
            }

            val segments = uri.pathSegments
            if (segments.size < 2) {
                emit(UpdateCheckState.Error(UpdateException("Invalid GitHub URL")))
                return@flow
            }

            val owner = segments[0]
            val repo = segments[1]
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

            val request = Request.Builder()
                .url(apiUrl)
                .get()
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "OuterTune-Updater")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("GitHub API returned ${response.code}")
                }

                val body = response.body?.string() ?: throw UpdateException("Empty response from GitHub API")
                val json = JSONObject(body)

                // tag_name often like v1.2.3
                var latestName = json.optString("tag_name", json.optString("name", ""))
                if (latestName.startsWith("v") || latestName.startsWith("V")) latestName = latestName.substring(1)

                val releaseNotes = json.optString("body", null)

                // find asset download url
                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.contains("apk") || name.contains("app-release") || name.endsWith(".apk")) {
                            downloadUrl = url
                            break
                        }
                        if (downloadUrl == null) downloadUrl = url
                    }
                }

                if (downloadUrl == null) {
                    // fallback to releases download URL pattern
                    downloadUrl = "https://github.com/$owner/$repo/releases/latest/download/${uri.lastPathSegment}"
                }

                val cmp = try {
                    compareVersion(latestName, currentVersionName)
                } catch (e: Exception) {
                    0
                }

                val info = UpdateInfo(
                    latestVersionCode = 0,
                    latestVersionName = latestName,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    mandatory = false,
                    checksum = null
                )

                if (cmp > 0) {
                    emit(UpdateCheckState.UpdateAvailable(info))
                } else {
                    emit(UpdateCheckState.UpToDate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkForUpdates failed", e)
            emit(UpdateCheckState.Error(e))
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Estados posibles durante el proceso de descarga
 */
sealed class DownloadState {
    data class Downloading(val progress: Int) : DownloadState()
    data class Downloaded(val file: File) : DownloadState()
    data class Error(val exception: UpdateException) : DownloadState()
}

/**
 * Excepción personalizada para errores de actualización
 */
class UpdateException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)