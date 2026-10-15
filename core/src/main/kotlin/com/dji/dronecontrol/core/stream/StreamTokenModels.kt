package com.dji.dronecontrol.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
data class StreamTokenRequestBody(
    val channel: String,
    val role: String,
    val uid: Int,
)

@Serializable
data class StreamTokenResponseBody(
    val token: String,
    val channel: String,
    val uid: Int,
    @SerialName("expires_at")
    @Serializable(with = StreamTokenExpiresAtSerializer::class)
    val expiresAt: String? = null,
)

data class StreamPublisherToken(
    val token: String,
    val channel: String,
    val uid: Int,
    val expiresAt: String? = null,
)
