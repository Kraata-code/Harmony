/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DuckDuckGoService {
    companion object {
        private const val TAG = "DuckDuckGoService"

        // User-Agent más reciente y realista
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // Cliente HTTP más completo
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = HashMap<String, List<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .addInterceptor { chain ->
            val originalRequest = chain.request()

            // Headers completos de navegador real
            val requestWithHeaders = originalRequest.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
                // .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .header("Referer", "https://www.google.com/")
                .build()

            // Pequeño delay para parecer humano
            Thread.sleep((500..1500).random().toLong())

            chain.proceed(requestWithHeaders)
        }
        .build()

    /**
     * Busca un término con intentos y fallbacks
     */
    // Reemplaza la función `searchQuickAnswer` en tu GoogleScrapingService.kt
    suspend fun searchQuickAnswer(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // URL de la versión HTML estática de DuckDuckGo
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery&kl=es-es"

            Log.d(TAG, "Realizando búsqueda en DuckDuckGo HTML: $url")

            val request = Request.Builder()
                .url(url)
                // User-Agent crucial para parecer un navegador[citation:3]
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext "Error HTTP: ${response.code}"
            }

            val html = response.body?.string() ?: return@withContext "Respuesta vacía"
            return@withContext parseDuckDuckGoHtml(html) // Nueva función de parseo

        } catch (e: Exception) {
            Log.e(TAG, "Error en la búsqueda DuckDuckGo", e)
            return@withContext "No se pudo obtener información. Error: ${e.localizedMessage}"
        }
    }

    /**
     * Parsea el HTML de DuckDuckGo para extraer el primer snippet de resultado.
     * Basado en la estructura HTML estática[citation:3].
     */
    private fun parseDuckDuckGoHtml(html: String): String {
        return try {
            val doc = Jsoup.parse(html)

            // 1. Buscar todos los contenedores de resultados
            val results = doc.select("div.result__body, div.web-result")

            for (result in results) {
                // 2. Extraer el snippet de texto (descripción)
                val snippet = result.select("a.result__snippet, .result__snippet").text().trim()
                if (snippet.isNotEmpty() && snippet.length > 20) {
                    Log.d(TAG, "Snippet encontrado: ${snippet.take(80)}...")
                    // Limpiar texto: eliminar saltos de línea múltiples
                    return snippet.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                }
            }

            // 3. Fallback: Buscar cualquier texto descriptivo
            val fallback = doc.select("body").text().substringAfter("Enlaces").take(300).trim()
            if (fallback.isNotEmpty()) {
                return fallback
            }

            "No se encontraron resultados claros en la página."
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear HTML de DuckDuckGo", e)
            "Error procesando la respuesta de DuckDuckGo."
        }
    }

    /**
     * Detecta páginas de verificación/CAPTCHA
     */
    private fun isVerificationPage(html: String): Boolean {
        return html.contains("Haz clic aquí si no vuelves a acceder", ignoreCase = true) ||
                html.contains("click here if you are not redirected", ignoreCase = true) ||
                html.contains("captcha", ignoreCase = true) ||
                html.contains("recaptcha", ignoreCase = true) ||
                html.contains("verification", ignoreCase = true) ||
                html.contains("unusual traffic", ignoreCase = true)
    }

    /**
     * Parsea HTML según la estrategia
     */
    private fun parseHtml(html: String): String {
        return try {
            val doc: Document = Jsoup.parse(html)
            parseDuckDuckGoHtml(doc)
        } catch (e: Exception) {
            "Error parseando respuesta"
        }
    }

    /**
     * Parsea DuckDuckGo
     */
    private fun parseDuckDuckGoHtml(doc: Document): String {
        // Resultados de DuckDuckGo
        val result = doc.select(
            """
            .result__snippet,
            .snippet,
            .web-result__snippet,
            .abstract
        """
        ).first()

        return result?.text()?.trim() ?: "No hay snippet en DuckDuckGo"
    }

    /**
     * Parsea Wikipedia
     */
    private fun parseWikipediaHtml(doc: Document): String {
        // Primer párrafo de artículo de Wikipedia
        val content = doc.select(
            """
            .mw-parser-output p,
            .mw-content-ltr p,
            #mw-content-text p
        """
        ).first()
        return content?.text()?.trim() ?: "Artículo de Wikipedia no encontrado"
    }

    /**
     * Verifica si el resultado es válido
     */
    private fun isValidResult(result: String): Boolean {
        return result.isNotEmpty() &&
                !result.startsWith("Error") &&
                !result.startsWith("No se pudo") &&
                !result.contains("Verificación requerida") &&
                !result.contains("CAPTCHA") &&
                result.length > 20
    }
}
