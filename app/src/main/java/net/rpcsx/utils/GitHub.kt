package net.rpcsx.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.remember
import androidx.core.content.edit
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object GitHub {
    const val server = "https://github.com/"
    const val apiServer = "https://api.github.com/"

    private val httpClient = HttpClient {
        install(Logging) {
            level = LogLevel.ALL
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 1000 * 60 * 10
            socketTimeoutMillis = 1000 * 60 * 10
            connectTimeoutMillis = 1000 * 5
        }

        expectSuccess = true
    }

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("github_cache", Context.MODE_PRIVATE)

        prefs.all.forEach {
            cache.entries[it.key] = Json.decodeFromString(it.value as String)
        }
    }

    @Serializable
    data class Release(
        val name: String,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    data class Asset(
        val name: String,
        val browser_download_url: String?
    )

    @Serializable
    data class CacheEntry(
        val timestamp: Long,
        val content: String,
    )

    data class Cache(
        val entries: HashMap<String, CacheEntry> = HashMap()
    )

    private val cache = Cache()

    sealed class DownloadStatus {
        data object Success : DownloadStatus()
        data class Error(val message: String?) : DownloadStatus()
    }

    sealed class FetchResult {
        data class Success<T>(val content: T) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    sealed class GetResult {
        data class Success(val content: String) : GetResult()
        data class Error(val status: HttpStatusCode) : GetResult()
    }

    private fun getCached(url: String, timestamp: Long): String? {
        val result = cache.entries[url]
        if (result == null || result.timestamp + 1000 * 60 * 10 < timestamp) {
            return null
        }

        return result.content
    }

    suspend fun get(url: String): GetResult {
        val timestamp = System.currentTimeMillis()
        val cached = getCached(url, timestamp)
        if (cached != null) {
            return GetResult.Success(cached)
        }

        val response: HttpResponse = withContext(Dispatchers.IO) {
            httpClient.get(url)
        }

        if (response.status.value != 200) return GetResult.Error(response.status)

        val body = response.body<String>()
        val cacheEntry = CacheEntry(timestamp, body)
        cache.entries[url] = cacheEntry
        prefs.edit {
            putString(url, Json.encodeToString(cacheEntry))
        }
        return GetResult.Success(body)
    }

    suspend fun fetchLatestRelease(repoUrl: String): FetchResult {
        val repoPath = repoUrl.removePrefix(
            server
        )
        val apiUrl = "${apiServer}repos/$repoPath/releases/latest"
        return try {
            when (val response = get(apiUrl)) {
                is GetResult.Error -> return FetchResult.Error("Failed to fetch drivers, ${response.status}")
                is GetResult.Success -> {
                    Log.e("GitHub", "response: " + response.content)
                    FetchResult.Success(json.decodeFromString<Release>(response.content))
                }
            }
        } catch (e: Exception) {
            Log.e("GitHub", "Error fetching releases: ${e.message}", e)
            FetchResult.Error("Error fetching releases: ${e.message}")
        }
    }

    suspend fun fetchReleases(repoUrl: String): FetchResult {
        val repoPath = repoUrl.removePrefix(
            server
        )
        val apiUrl = "${apiServer}repos/$repoPath/releases"
        return try {
            when (val response = get(apiUrl)) {
                is GetResult.Error -> return FetchResult.Error("Failed to fetch drivers, ${response.status}")
                is GetResult.Success -> {
                    Log.e("GitHub", "response: " + response.content)
                    val releases = json.decodeFromString<List<Release>>(response.content)
                    val drivers = releases.map { release ->
                        val assetUrl = release.assets.firstOrNull()?.browser_download_url
                        release.name to assetUrl
                    }
                    FetchResult.Success(drivers)
                }
            }
        } catch (e: Exception) {
            Log.e("GitHub", "Error fetching releases: ${e.message}", e)
            FetchResult.Error("Error fetching releases: ${e.message}")
        }
    }

    suspend fun downloadAsset(
        assetUrl: String, destinationFile: File, progressCallback: (Long, Long) -> Unit
    ): DownloadStatus {
        Log.w("GitHub", "Downloading asset $assetUrl")
        return try {
            withContext(Dispatchers.IO) {
                httpClient.prepareGet(assetUrl).execute { response ->
                    val contentLength = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                    val written = FileOutputStream(destinationFile).use { outputStream ->
                        writeResponseToStream(response, outputStream, contentLength, progressCallback)
                    }

                    if (written != contentLength) {
                        DownloadStatus.Error("Connection closed")
                    } else {
                        DownloadStatus.Success
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GitHub", "Error downloading file: ${e.message}", e)
            DownloadStatus.Error(e.message)
        }
    }

    private suspend fun writeResponseToStream(
        response: HttpResponse,
        outputStream: OutputStream,
        contentLength: Long,
        progressCallback: (Long, Long) -> Unit
    ) : Long {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(1024) // 1KB buffer size
        var totalBytesRead = 0L

        while (!channel.isClosedForRead) {
            val bytesRead = channel.readAvailable(buffer)
            if (bytesRead > 0) {
                withContext(Dispatchers.IO) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                totalBytesRead += bytesRead
                progressCallback(totalBytesRead, contentLength)
            }
        }
        withContext(Dispatchers.IO) {
            outputStream.flush()
        }

        return totalBytesRead
    }
}