package com.example.mediadownloader.resolver

interface MediaResolver {
    fun canHandle(url: String): Boolean
    suspend fun resolve(url: String, preferredFormat: String = "MP3"): ResolveResult
    suspend fun resolveMediaStream(url: String, format: String): MediaStream?
}

class CompositeMediaResolver(
    private val resolvers: List<MediaResolver>
) : MediaResolver {

    override fun canHandle(url: String): Boolean {
        return resolvers.any { it.canHandle(url) }
    }

    override suspend fun resolve(url: String, preferredFormat: String): ResolveResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return ResolveResult.Error("URL cannot be empty")
        }

        val handler = resolvers.firstOrNull { it.canHandle(trimmed) }
        return if (handler != null) {
            handler.resolve(trimmed, preferredFormat)
        } else {
            ResolveResult.Unsupported("No compatible resolver found for URL: $trimmed")
        }
    }

    override suspend fun resolveMediaStream(url: String, format: String): MediaStream? {
        val trimmed = url.trim()
        val handler = resolvers.firstOrNull { it.canHandle(trimmed) }
        return handler?.resolveMediaStream(trimmed, format)
    }
}
