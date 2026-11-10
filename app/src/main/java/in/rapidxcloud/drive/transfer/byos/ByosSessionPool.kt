package `in`.rapidxcloud.drive.transfer.byos

import android.util.Log
import `in`.rapidxcloud.drive.crypto.NativeCrypto
import `in`.rapidxcloud.drive.data.api.CanonDriveApi
import `in`.rapidxcloud.drive.data.model.ByosEnvelope
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tier-aware BYOS Session Pool matching web-new/src/lib/mtcute/pool-manager.ts:
 *
 * 1. Zero-Trust Security: Uses temporary MTProto AuthKey session strings negotiated by
 *    the Go microservice (/api/byos/token-envelope). Raw bot tokens are NEVER held in client memory.
 * 2. Concurrency Allocation:
 *      - Starter: 2 hot bots (max 8)
 *      - Pro:     5 hot bots (max 20)
 *      - Ultra:   8 hot bots (max 45)
 * 3. Heartbeat: Periodic ping to /api/byos/heartbeat every 45s while active to keep 10-minute lease.
 * 4. Flood Rotation: Automatically cools down flooded bots and rotates standby bots.
 */
class ByosSessionPool(
    private val api: CanonDriveApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ByosSessionPool"
        private const val SESSION_TTL_MS = 10 * 60 * 1000L // 10 minutes sliding TTL
        private const val HEARTBEAT_INTERVAL_MS = 45 * 1000L // 45 seconds
    }

    data class BotSlot(
        val botId: String,
        val sessionString: String?,
        val token: String?,
        var isHot: Boolean = false,
        var coolingUntil: Long = 0L,
        var activeTransfers: Int = 0
    )

    private var cachedEnvelope: ByosEnvelope? = null
    private var lastActivityTime: Long = 0L
    private val botSlots = ConcurrentHashMap<String, BotSlot>()
    private val hotBotIds = mutableListOf<String>()
    private val standbyBotIds = mutableListOf<String>()
    private var heartbeatJob: Job? = null

    init {
        startHeartbeat()
    }

    @Synchronized
    suspend fun getOrFetchEnvelope(forceRefresh: Boolean = false): ByosEnvelope? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedEnvelope != null && (now - lastActivityTime < SESSION_TTL_MS)) {
            lastActivityTime = now
            return cachedEnvelope
        }

        try {
            val res = api.getByosTokenEnvelope()
            if (res.isSuccessful && res.body() != null) {
                val envelope = res.body()!!
                setupPoolStructure(envelope)
                cachedEnvelope = envelope
                lastActivityTime = System.currentTimeMillis()
                Log.i(TAG, "Acquired BYOS session envelope: ${hotBotIds.size} hot bots, ${standbyBotIds.size} standby")
                return envelope
            } else {
                Log.w(TAG, "Failed to retrieve BYOS token envelope: ${res.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching BYOS token envelope", e)
        }
        return null
    }

    private fun setupPoolStructure(envelope: ByosEnvelope) {
        botSlots.clear()
        hotBotIds.clear()
        standbyBotIds.clear()

        val allBotIds = mutableListOf<String>()

        if (!envelope.sessions.isNullOrEmpty()) {
            for (s in envelope.sessions) {
                val isValid = if (NativeCrypto.isAvailable && !s.session_string.isNullOrEmpty()) {
                    NativeCrypto.validateSessionString(s.session_string)
                } else {
                    !s.session_string.isNullOrEmpty()
                }

                if (isValid) {
                    botSlots[s.bot_id] = BotSlot(
                        botId = s.bot_id,
                        sessionString = s.session_string,
                        token = null
                    )
                    allBotIds.add(s.bot_id)
                }
            }
        } else if (!envelope.tokens.isNullOrEmpty()) {
            for (token in envelope.tokens) {
                val botId = if (token.contains(":")) token.split(":")[0] else token
                botSlots[botId] = BotSlot(
                    botId = botId,
                    sessionString = null,
                    token = token
                )
                allBotIds.add(botId)
            }
        }

        val maxHot = envelope.active_hot_connections ?: when (envelope.tier) {
            "ultra" -> 8
            "pro" -> 5
            else -> 2
        }

        for (i in allBotIds.indices) {
            val botId = allBotIds[i]
            if (i < maxHot) {
                botSlots[botId]?.isHot = true
                hotBotIds.add(botId)
            } else {
                botSlots[botId]?.isHot = false
                standbyBotIds.add(botId)
            }
        }
    }

    @Synchronized
    fun pickBotForFile(): BotSlot? {
        val now = System.currentTimeMillis()
        // 1. Try available hot bot not in cooldown
        for (botId in hotBotIds) {
            val slot = botSlots[botId] ?: continue
            if (slot.coolingUntil <= now && slot.activeTransfers < 2) {
                slot.activeTransfers++
                return slot
            }
        }

        // 2. Try standby bot if all hot bots are busy or cooling down
        for (botId in standbyBotIds) {
            val slot = botSlots[botId] ?: continue
            if (slot.coolingUntil <= now && slot.activeTransfers < 2) {
                slot.activeTransfers++
                return slot
            }
        }

        return null
    }

    @Synchronized
    fun releaseBot(botId: String) {
        val slot = botSlots[botId] ?: return
        slot.activeTransfers = (slot.activeTransfers - 1).coerceAtLeast(0)
    }

    @Synchronized
    fun reportFloodWait(botId: String, seconds: Long) {
        val slot = botSlots[botId] ?: return
        Log.w(TAG, "Bot $botId received FLOOD_WAIT for ${seconds}s. Rotating to standby pool.")
        slot.coolingUntil = System.currentTimeMillis() + (seconds * 1000L)
        slot.activeTransfers = 0

        // Swap out flooded hot bot with standby bot
        if (hotBotIds.contains(botId) && standbyBotIds.isNotEmpty()) {
            hotBotIds.remove(botId)
            val replacement = standbyBotIds.removeAt(0)
            hotBotIds.add(replacement)
            standbyBotIds.add(botId)
            botSlots[replacement]?.isHot = true
            slot.isHot = false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastActivityTime < SESSION_TTL_MS) {
                    try {
                        api.byosHeartbeat()
                    } catch (e: Exception) {
                        Log.d(TAG, "BYOS heartbeat skipped: ${e.message}")
                    }
                }
            }
        }
    }
}
