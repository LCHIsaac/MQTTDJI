package com.dji.dronecontrol.core.stream

/** Test double: returns fixed publisher/audience tokens without HTTP. */
class DualRoleStreamTokenFetcher(
    private val publisherToken: String = "publisher-token-secret",
    private val audienceToken: String = "audience-token-for-viewer",
    private val audienceResult: StreamTokenFetchResult? = null,
) : StreamTokenFetcher {

    override suspend fun fetchPublisherToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
        uid: Int,
    ): StreamTokenFetchResult = StreamTokenFetchResult.Success(
        StreamPublisherToken(token = publisherToken, channel = channel, uid = uid),
    )

    override suspend fun fetchAudienceToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
    ): StreamTokenFetchResult = audienceResult ?: StreamTokenFetchResult.Success(
        StreamPublisherToken(token = audienceToken, channel = channel, uid = HttpStreamTokenFetcher.AUDIENCE_UID),
    )
}
