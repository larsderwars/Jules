package dev.therealashik.jules.sdk

import dev.therealashik.jules.sdk.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class JulesApiClient(
    private val apiKey: String,
    httpClient: HttpClient? = null
) {
    private val cache = InMemoryCache<String, Any>()

    private val client = (httpClient ?: HttpClient()).config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
        defaultRequest {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
        }
    }

    private val baseUrl = "https://jules.googleapis.com/v1alpha"

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (!status.isSuccess()) {
            throw Exception("API Error: ${status.value} - ${bodyAsText()}")
        }
        return body()
    }

    suspend fun createSession(request: CreateSessionRequest): Session {
        val response = client.post("$baseUrl/sessions") {
            setBody(request)
        }
        return response.bodyOrThrow<Session>().also {
            cache.removeMatching { it.startsWith("listSessions-") }
        }
    }

    suspend fun listSessions(
        pageSize: Int = 30,
        pageToken: String? = null,
        useCache: Boolean = true
    ): ListSessionsResponse {
        val safePageSize = pageSize.coerceIn(1, 100)
        val cacheKey = "listSessions-$safePageSize-$pageToken"
        val cached = cache.get(cacheKey) as? ListSessionsResponse
        if (useCache && cached != null) return cached

        val response = client.get("$baseUrl/sessions") {
            parameter("pageSize", safePageSize)
            pageToken?.let { parameter("pageToken", it) }
        }
        return response.bodyOrThrow<ListSessionsResponse>().also {
            cache.set(cacheKey, it)
        }
    }

    suspend fun getSession(sessionId: String, forceRefresh: Boolean = false): Session {
        val cacheKey = "getSession-$sessionId"
        if (!forceRefresh) {
            val cached = cache.get(cacheKey) as? Session
            if (cached != null) return cached
        }

        val response = client.get("$baseUrl/sessions/$sessionId")
        return response.bodyOrThrow<Session>().also {
            cache.set(cacheKey, it)
        }
    }

    suspend fun deleteSession(sessionId: String) {
        val response = client.delete("$baseUrl/sessions/$sessionId")
        if (!response.status.isSuccess()) {
            throw Exception("API Error: ${response.status.value} - ${response.bodyAsText()}")
        }
        cache.removeMatching { it.contains(sessionId) || it.startsWith("listSessions-") }
    }

    suspend fun sendMessage(sessionId: String, request: SendMessageRequest): SendMessageResponse {
        val response = client.post("$baseUrl/sessions/$sessionId:sendMessage") {
            setBody(request)
        }
        return response.bodyOrThrow<SendMessageResponse>().also {
            cache.removeMatching { key -> key.contains(sessionId) || key.startsWith("listSessions-") }
        }
    }

    suspend fun approvePlan(sessionId: String, plan: Plan? = null): ApprovePlanResponse {
        val response = client.post("$baseUrl/sessions/$sessionId:approvePlan") {
            setBody(ApprovePlanRequest(plan = plan))
        }
        return response.bodyOrThrow<ApprovePlanResponse>().also {
            cache.removeMatching { key -> key.contains(sessionId) || key.startsWith("listSessions-") }
        }
    }

    suspend fun listActivities(
        sessionId: String,
        pageSize: Int = 50,
        pageToken: String? = null,
        createTime: String? = null,
        forceRefresh: Boolean = false
    ): ListActivitiesResponse {
        val safePageSize = pageSize.coerceIn(1, 100)
        val cacheKey = "listActivities-$sessionId-$safePageSize-$pageToken-$createTime"
        if (!forceRefresh) {
            val cached = cache.get(cacheKey) as? ListActivitiesResponse
            if (cached != null) return cached
        }

        val response = client.get("$baseUrl/sessions/$sessionId/activities") {
            parameter("pageSize", safePageSize)
            pageToken?.let { parameter("pageToken", it) }
            createTime?.let { parameter("createTime", it) }
        }
        return response.bodyOrThrow<ListActivitiesResponse>().also {
            cache.set(cacheKey, it)
        }
    }

    suspend fun getActivity(sessionId: String, activityId: String, useCache: Boolean = true): Activity {
        val cacheKey = "getActivity-$sessionId-$activityId"
        val cached = cache.get(cacheKey) as? Activity
        if (useCache && cached != null) return cached

        val response = client.get("$baseUrl/sessions/$sessionId/activities/$activityId")
        return response.bodyOrThrow<Activity>().also {
            cache.set(cacheKey, it)
        }
    }

    suspend fun listSources(
        pageSize: Int = 30,
        pageToken: String? = null,
        filter: String? = null,
        useCache: Boolean = true
    ): ListSourcesResponse {
        val safePageSize = pageSize.coerceIn(1, 100)
        val cacheKey = "listSources-$safePageSize-$pageToken-$filter"
        val cached = cache.get(cacheKey) as? ListSourcesResponse
        if (useCache && cached != null) return cached

        val response = client.get("$baseUrl/sources") {
            parameter("pageSize", safePageSize)
            pageToken?.let { parameter("pageToken", it) }
            filter?.let { parameter("filter", it) }
        }
        return response.bodyOrThrow<ListSourcesResponse>().also {
            cache.set(cacheKey, it)
        }
    }

    suspend fun getSource(sourceId: String, useCache: Boolean = true): Source {
        val cacheKey = "getSource-$sourceId"
        val cached = cache.get(cacheKey) as? Source
        if (useCache && cached != null) return cached

        val response = client.get("$baseUrl/sources/$sourceId")
        return response.bodyOrThrow<Source>().also {
            cache.set(cacheKey, it)
        }
    }

    fun close() {
        client.close()
    }
}
