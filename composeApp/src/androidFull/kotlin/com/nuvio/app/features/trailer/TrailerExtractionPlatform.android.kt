package com.nuvio.app.features.trailer

import android.net.Uri
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
        preferFastStart: Boolean = false,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.selectedVariantUrl
        } else {
            bestProgressive?.url
        }

        if (preferFastStart) {
            // Fastest possible startup: use the 360p muxed progressive MP4 directly.
            // Single URL, no audio separation, no CDN probing needed.
            val progressiveUrl = bestProgressive?.url ?: combinedUrl
            if (progressiveUrl != null) {
                return@withContext TrailerPlaybackSource(
                    videoUrl = resolveReachableUrlOrNull(progressiveUrl) ?: return@withContext null,
                    audioUrl = null,
                )
            }
        }

        // Quality path: probe video and audio CDN servers in parallel so the 2s
        // probe budget is shared rather than paid twice sequentially.
        val videoBaseUrl = bestVideo?.url ?: combinedUrl ?: return@withContext null
        val audioBaseUrl = bestAudio?.url

        val (videoUrl, audioUrl) = coroutineScope {
            val videoJob = async { resolveReachableUrlOrNull(videoBaseUrl) }
            val audioJob = audioBaseUrl?.let { url -> async { resolveReachableUrlOrNull(url) } }
            videoJob.await() to audioJob?.await()
        }

        if (videoUrl == null) return@withContext null

        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    private fun getUserAgentForUrl(url: String): String {
        val lowercaseUrl = url.lowercase()
        return when {
            lowercaseUrl.contains("c=android_vr") -> "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip"
            lowercaseUrl.contains("c=android") -> "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip"
            lowercaseUrl.contains("c=ios") -> "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)"
            else -> defaultHeaders.getValue("user-agent")
        }
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn") ?: return url
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) {
            return if (isUrlReachable(url)) url else null
        }

        val host = uri.host ?: return if (isUrlReachable(url)) url else null
        val candidates = mutableListOf(url)
        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9-]+"), server)
            if (altHost != host) {
                candidates += url.replace(host, altHost)
            }
        }

        if (candidates.size == 1) {
            return if (isUrlReachable(candidates[0])) candidates[0] else null
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            withTimeoutOrNull(2_000L) { result.await() }
        } finally {
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String): Boolean {
        return runCatching {
            val headersMap = defaultHeaders.toMutableMap().apply {
                put("user-agent", getUserAgentForUrl(url))
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .headers(buildHeaders(headersMap))
                .build()

            probeClient.newCall(request).execute().use { response ->
                response.code in 200..299
            }
        }.getOrDefault(false)
    }

    // Android's ExoPlayer supports WebM/Opus and VP9/AV1 natively — no filtering needed.
    fun filterAudioCandidates(candidates: List<StreamCandidate>): List<StreamCandidate> = candidates
    fun filterVideoCandidates(candidates: List<StreamCandidate>): List<StreamCandidate> = candidates

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}