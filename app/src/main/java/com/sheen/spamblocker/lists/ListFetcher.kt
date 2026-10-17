package com.sheen.spamblocker.lists

import com.sheen.spamblocker.data.ListSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

sealed interface FetchResult {
    data class Body(val text: String, val etag: String?, val lastModified: String?) : FetchResult
    data object NotModified : FetchResult

    /** [retryable] is true for network errors and 5xx responses. */
    data class Failure(val message: String, val retryable: Boolean) : FetchResult
}

/**
 * Downloads one list with a conditional GET (SPEC §8.3). This is the app's
 * only network access (SPEC §3).
 */
class ListFetcher @Inject constructor(private val client: OkHttpClient) {

    suspend fun fetch(source: ListSource, maxBytes: Long = ListParser.MAX_DOWNLOAD_BYTES): FetchResult =
        withContext(Dispatchers.IO) {
            val url = source.url.trim().toHttpUrlOrNull()
                ?: return@withContext FetchResult.Failure("Not an http(s) URL", retryable = false)
            val request = Request.Builder().url(url).apply {
                source.etag?.let { header("If-None-Match", it) }
                source.lastModified?.let { header("If-Modified-Since", it) }
            }.build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 304 -> FetchResult.NotModified
                        !response.isSuccessful ->
                            FetchResult.Failure("HTTP ${response.code}", retryable = response.code >= 500)
                        else -> {
                            val body = response.body
                            if (body.contentLength() > maxBytes) {
                                return@use FetchResult.Failure(tooLarge(maxBytes), retryable = false)
                            }
                            // Content-Length may be missing or wrong, so also cap what is read.
                            val stream = body.source()
                            if (stream.request(maxBytes + 1)) {
                                return@use FetchResult.Failure(tooLarge(maxBytes), retryable = false)
                            }
                            FetchResult.Body(
                                text = stream.buffer.readUtf8(),
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                FetchResult.Failure(e.message ?: e.javaClass.simpleName, retryable = true)
            }
        }

    private fun tooLarge(maxBytes: Long): String {
        val mb = 1024 * 1024
        val limit = if (maxBytes >= mb) "${maxBytes / mb} MB" else "$maxBytes bytes"
        return "List is larger than $limit"
    }
}
