package com.multivpn.app.engine

import com.multivpn.app.model.Protocol
import com.multivpn.app.model.VpnProfile

/** Future TUN engines implement this boundary; never report a simulated connection. */
interface ProtocolEngine {
    val supportedProtocols: Set<Protocol>
    suspend fun connect(profile: VpnProfile): ConnectionResult
    suspend fun disconnect()
}

sealed interface ConnectionResult {
    data object Connected : ConnectionResult
    data class Unavailable(val reason: String) : ConnectionResult
    data class Failed(val safeMessage: String) : ConnectionResult
}

/** All backends are unimplemented in V0.1; fail closed. */
class EngineRegistry(private val engines: List<ProtocolEngine> = emptyList()) {
    fun availableFor(protocol: Protocol): Boolean =
        engines.any { protocol in it.supportedProtocols }

    suspend fun connect(profile: VpnProfile): ConnectionResult {
        val engine = engines.firstOrNull { profile.protocol in it.supportedProtocols }
            ?: return ConnectionResult.Unavailable("尚未集成 ${profile.protocol.label} 连接核心")
        return engine.connect(profile)
    }
}
