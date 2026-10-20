package com.multivpn.app.model

import java.util.UUID

data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val endpoint: String,
    val originalConfig: String,
    val importedAt: Long = System.currentTimeMillis()
)

/** Stored preference, NOT active device networking rules in V0.1. */
data class RoutePolicy(
    val chinaDirect: Boolean = true,
    val primaryId: String? = null,
    val backupId: String? = null
)
