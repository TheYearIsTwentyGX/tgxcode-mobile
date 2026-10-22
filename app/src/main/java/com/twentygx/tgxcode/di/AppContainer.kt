package com.twentygx.tgxcode.di

import android.content.Context
import com.twentygx.tgxcode.data.attach.AttachmentSource
import com.twentygx.tgxcode.data.attach.Uploader
import com.twentygx.tgxcode.data.install.PhoneInstaller
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.OrchestratorStore
import com.twentygx.tgxcode.data.repo.StatusStore
import com.twentygx.tgxcode.data.repo.WorkspaceStore
import com.twentygx.tgxcode.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Manual dependency injection. Six objects and one graph — Hilt would be more
 * ceremony than the problem has.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context.applicationContext)

    /**
     * The active config, kept hot so the interceptors can read it synchronously.
     * DataStore is suspend-only, and an interceptor cannot suspend.
     */
    val config: StateFlow<BridgeConfig?> =
        settings.config.stateIn(appScope, SharingStarted.Eagerly, null)

    val stayConnected: StateFlow<Boolean> =
        settings.stayConnected.stateIn(appScope, SharingStarted.Eagerly, false)

    private val clients = BridgeClients(
        tokenProvider = { config.value?.token },
        accessProvider = { config.value?.access },
    )

    val api = BridgeApi(clients, configProvider = { config.value })

    private val sse = BridgeSse(clients, configProvider = { config.value })

    val repository = BridgeRepository(api, sse, appScope)

    /** Quota and the notification log — account-wide, hung off [repository]'s fan-out. */
    val status = StatusStore(api, repository, appScope)

    /** Scheduled messages, snippets and schedules — whole payloads, pushed. */
    val workspace = WorkspaceStore(api, repository, appScope)

    /** Orchestrators: each one's payload, and the list a task can be handed to. */
    val orchestrators = OrchestratorStore(api, repository, appScope)

    /** APKs the desktop queues for this phone, installed through PackageInstaller. */
    val phoneInstalls = PhoneInstaller(appContext, api, repository, settings, config, appScope)

    /** Reads picked files off the phone. */
    val attachmentSource = AttachmentSource(appContext)

    /** Sends them up, one per request. */
    val uploader = Uploader(attachmentSource, api)

    /** Called from the connectivity callback: pooled sockets outlive their network. */
    fun onNetworkChanged() = clients.evictPooledConnections()

    /** For answering from a notification, where the ANR budget is ~10s. */
    val notificationClient get() = clients.notificationAction
}
