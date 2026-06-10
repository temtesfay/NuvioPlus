package com.nuvio.app.features.trailer

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
    )

    private val httpClient = HttpClient(Darwin) {
        install(HttpTimeout)
        followRedirects = true
        expectSuccess = false
    }

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.Default) {
        val response = httpClient.request(url) {
            this.method = when (method.uppercase()) {
                "POST" -> HttpMethod.Post
                "PUT" -> HttpMethod.Put
                "DELETE" -> HttpMethod.Delete
                else -> HttpMethod.Get
            }
            headers.forEach { (name, value) ->
                header(name, value)
            }
            if (body != null) {
                setBody(body)
            }
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "" }
        TrailerRequestResponse(
            ok = response.status.isSuccess(),
            status = response.status.value,
            statusText = response.status.description,
            url = response.request.url.toString(),
            body = bodyText,
        )
    }

    // AVFoundation (used by the hero trailer player) only supports AAC for external
    // audio streams — WebM/Opus is not decoded. Prefer AAC by *codec* (not just the
    // m4a container); fall back to the m4a container, then the full list.
    fun filterAudioCandidates(candidates: List<StreamCandidate>): List<StreamCandidate> {
        val aacCandidates = candidates.filter { it.codec == "aac" }
        if (aacCandidates.isNotEmpty()) return aacCandidates
        val m4aCandidates = candidates.filter { it.ext == "m4a" }
        return m4aCandidates.ifEmpty { candidates }
    }

    // AVFoundation only decodes H.264 and HEVC — it cannot handle VP9/AV1/VP8.
    // Filtering on the MP4 *container* alone is not enough: YouTube ships 1440p/2160p
    // video as AV1 inside an MP4 container, which AVPlayer accepts but renders black
    // (audio plays, no video — e.g. Stranger Things' 2160p AV1 stream). Filter on the
    // actual codec family. Prefer H.264 (caps at 1080p, universally decodable), then
    // HEVC, then fall back to the MP4 container, then all candidates.
    fun filterVideoCandidates(candidates: List<StreamCandidate>): List<StreamCandidate> {
        val h264Candidates = candidates.filter { it.codec == "h264" }
        if (h264Candidates.isNotEmpty()) return h264Candidates
        val h265Candidates = candidates.filter { it.codec == "h265" }
        if (h265Candidates.isNotEmpty()) return h265Candidates
        val mp4Candidates = candidates.filter { it.ext == "mp4" }
        return mp4Candidates.ifEmpty { candidates }
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
        preferFastStart: Boolean = false,
    ): TrailerPlaybackSource? = withContext(Dispatchers.Default) {
        // Mirror upstream Android logic: prefer the adaptive split-stream (bestVideo +
        // bestAudio) over an HLS manifest. The android_vr client (PREFERRED_SEPARATE_CLIENT)
        // returns H.264/mp4 video and AAC/m4a audio — both decodable by AVFoundation.
        // HLS is kept as a fallback when no adaptive video is available.
        val bestManifestHeight = bestManifest?.height ?: -1
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifestHeight > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.manifestUrl
        } else {
            bestProgressive?.url
        }

        if (preferFastStart) {
            // Home hero slide 0: fastest possible startup. Use the 360p muxed progressive
            // MP4 — single AVPlayer, audio in-band, small file, reliably ready in 1-2s.
            val progressiveUrl = bestProgressive?.url ?: combinedUrl
            if (progressiveUrl != null) {
                return@withContext TrailerPlaybackSource(
                    videoUrl = resolveReachableUrl(progressiveUrl),
                    audioUrl = null,
                )
            }
        }

        // Quality path: adaptive split-stream gives 720p/1080p H.264 + AAC.
        // Probe both URLs in parallel so the 2s CDN probe budget is shared rather
        // than paid twice sequentially — cuts loading time roughly in half.
        val videoBaseUrl = bestVideo?.url ?: combinedUrl ?: return@withContext null
        val audioBaseUrl = if (bestVideo != null) bestAudio?.url else null

        val (videoUrl, audioUrl) = coroutineScope {
            val videoJob = async { resolveReachableUrl(videoBaseUrl) }
            val audioJob = audioBaseUrl?.let { url -> async { resolveReachableUrl(url) } }
            videoJob.await() to audioJob?.await()
        }

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

    private suspend fun resolveReachableUrl(url: String): String {
        if (!url.contains("googlevideo.com")) return url

        val mnParam = getQueryParameter(url, "mn") ?: return url
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) return url

        val host = getHost(url) ?: return url
        val candidates = mutableListOf(url)

        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9-]+"), server)
            if (altHost != host) {
                candidates += url.replace(host, altHost)
            }
        }

        if (candidates.size == 1) return candidates.first()

        // Race all probes — return as soon as the first one succeeds rather than
        // waiting for all to complete. On slow networks where DNS or one CDN server
        // stalls, awaitAll() would sit at the 2s limit even when another candidate
        // responded in 100ms.
        return coroutineScope {
            val result = CompletableDeferred<String>()
            val jobs = candidates.map { candidate ->
                launch {
                    if (isUrlReachable(candidate)) result.complete(candidate)
                }
            }
            val winner = withTimeoutOrNull(2_000L) { result.await() }
            jobs.forEach { it.cancel() }
            winner ?: url
        }
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        val response = runCatching {
            performRequest(
                url = url,
                method = "GET",
                headers = mapOf(
                    "range" to "bytes=0-0",
                    "user-agent" to getUserAgentForUrl(url),
                ),
                body = null,
                timeoutMillis = 2_000L,
            )
        }.getOrNull() ?: return false

        return response.status in 200..299
    }

    private fun getHost(url: String): String? {
        val components = NSURLComponents(string = url)
        return components.host
    }

    private fun getQueryParameter(url: String, name: String): String? {
        val components = NSURLComponents(string = url)
        return queryItems(components).firstOrNull { it.name == name }?.value
    }

    private fun queryItems(components: NSURLComponents): List<NSURLQueryItem> {
        return components.queryItems?.mapNotNull { it as? NSURLQueryItem } ?: emptyList()
    }
}