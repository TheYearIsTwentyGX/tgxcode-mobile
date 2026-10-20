package com.twentygx.tgxcode.data.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.isConflict
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.settings.SettingsStore
import com.twentygx.tgxcode.service.InstallNotifications
import com.twentygx.tgxcode.service.InstallResultReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the APKs the desktop queues for this phone.
 *
 * Agents on the desktop used to `adb install` over wireless debugging, which turns
 * itself off. This app already holds an authenticated connection to the bridge from
 * any network, so the bridge files a `pending` record instead and this picks it up:
 * claim it, download it, check it, and hand it to PackageInstaller — reporting each
 * step back so the agent that asked can see how it went.
 *
 * Hangs off [BridgeRepository]'s fan-out like the other stores, so it rides whatever
 * connection is already open — the UI's, or the background service's — and adds no
 * service of its own. Installs run one at a time as far as the commit, so two
 * downloads never fight over a weak link; a confirmation still waiting on a tap does
 * not hold up the next one.
 *
 * **Silent, or one tap.** `USER_ACTION_NOT_REQUIRED` only holds for an update to a
 * package this app is already the installer of record for — which includes itself.
 * Anything else comes back `STATUS_PENDING_USER_ACTION` with an Intent to confirm,
 * which becomes a notification: from the background an app may not start an
 * activity, and the confirmation is one. So each app costs one tap, once.
 *
 * **Updating itself kills the process** that committed it, so `installing` is
 * reported before the commit and the result is worked out on the next start from
 * the persisted [Claim] — see [reconcile].
 */
class PhoneInstaller(
    context: Context,
    private val api: BridgeApi,
    private val repo: BridgeRepository,
    private val settings: SettingsStore,
    private val config: StateFlow<BridgeConfig?>,
    private val scope: CoroutineScope,
) {
    private val context: Context = context.applicationContext
    private val pm: PackageManager get() = context.packageManager

    val enabled: StateFlow<Boolean> = settings.installApps.stateIn(scope, SharingStarted.Eagerly, true)

    private val _canInstall = MutableStateFlow(pm.canRequestPackageInstalls())

    /** The "Install unknown apps" grant for this app. Re-read on every resume. */
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    /** Whether one of our activities is on screen, so a confirmation may open directly. */
    @Volatile
    private var foreground = false

    /** Ids being worked on in this process, from enqueue to commit. */
    private val active = mutableMapOf<String, Job>()
    private val activeLock = Any()

    /** One at a time, up to the commit — see the class comment. */
    private val serial = Mutex()

    init {
        scope.launch { repo.events.collect { if (it is StreamEvent.PhoneInstallChanged) onRecord(it.install) } }
        scope.launch { repo.resyncs.collect { catchUp() } }
        scope.launch {
            repo.pollRounds.collect { round ->
                if (round % BridgeRepository.INDEX_EVERY == 0) catchUp()
            }
        }
        // On start, with no connection needed: a self-update restarted this process,
        // perhaps from MY_PACKAGE_REPLACED with nothing else running, and the record
        // is still saying `installing` on the desk.
        scope.launch {
            config.filterNotNull().first()
            catchUp()
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** From the activity's onResume: the moment a missing grant may have been given. */
    fun onResume() {
        foreground = true
        val can = pm.canRequestPackageInstalls()
        _canInstall.value = can
        if (can) {
            InstallNotifications.cancelPermission(context)
            scope.launch { resumeWaiting() }
        }
    }

    fun onPause() {
        foreground = false
    }

    fun setEnabled(on: Boolean) = scope.launch { settings.setInstallApps(on) }

    // ------------------------------------------------------------- picking up

    private fun onRecord(record: PhoneInstall) {
        when (record.state) {
            PhoneInstallState.PENDING -> enqueue(record, claimed = false)
            // Taken back at the desk: stop whatever is still ours to stop. A session
            // already committed is Android's now and finishes on its own.
            PhoneInstallState.CANCELLED -> {
                synchronized(activeLock) { active[record.id] }?.cancel()
                scope.launch {
                    if (claimOf(record.id)?.waitingForPermission == true) {
                        forget(record.id)
                        apkFile(record.id).delete()
                        InstallNotifications.cancel(context, record.id)
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Read the queue and pick up anything missed while away. Nothing is replayed to a
     * client that was not connected, so a `pending` filed then is only ever found here.
     */
    private suspend fun catchUp() {
        val installs = api.phoneInstalls().getOrNull()?.installs ?: return
        reconcile(installs)
        if (!enabled.value) return
        val inHand = synchronized(activeLock) { active.keys.toSet() }
        PhoneInstallRules.toClaim(installs, inHand).forEach { enqueue(it, claimed = false) }
    }

    private fun enqueue(record: PhoneInstall, claimed: Boolean) {
        if (!enabled.value) return
        synchronized(activeLock) {
            if (record.id in active) return
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    serial.withLock { run(record, claimed) }
                } finally {
                    synchronized(activeLock) { active.remove(record.id) }
                }
            }
            active[record.id] = job
            job.start()
        }
    }

    // ------------------------------------------------------------- the work

    private fun apkFile(id: String) = File(context.cacheDir, "phone-installs/$id.apk")

    private suspend fun run(record: PhoneInstall, claimed: Boolean) {
        val id = record.id
        val label = record.label.ifBlank { record.fileName.ifBlank { "the app" } }
        if (!claimed) {
            val claim = report(id, PhoneInstallStatus(PhoneInstallState.DOWNLOADING))
            if (claim.isConflict) {
                Tgx.d("install $id: claimed elsewhere — dropping it")
                return
            }
            if (claim !is ApiResult.Ok) {
                // Still pending on the desk, so the next catch-up tries again.
                Tgx.w("install $id: could not claim it: ${claim.message()}")
                return
            }
            remember(Claim(id = id, claimedAt = System.currentTimeMillis(), label = label))
        }
        Tgx.d("install $id: downloading ${record.fileName} (${record.size} bytes)")

        val file = apkFile(id)
        try {
            if (!(file.exists() && PhoneInstallRules.verify(record, file.length(), sha256Of(file)) == null)) {
                when (val download = api.downloadPhoneInstall(id, file)) {
                    is ApiResult.Ok -> {
                        val wrong = PhoneInstallRules.verify(
                            record,
                            download.value.size,
                            download.value.sha256,
                            download.value.headerSha256,
                        )
                        if (wrong != null) return fail(id, label, wrong)
                    }
                    else -> return fail(id, label, "Download failed: ${download.message()}")
                }
            }

            val archive = withContext(Dispatchers.IO) { pm.getPackageArchiveInfo(file.path, 0) }
                ?: return fail(id, label, "Android could not read ${record.fileName} as an APK.")
            val packageName = archive.packageName
            val versionName = archive.versionName
            val app = appName(archive, file) ?: label
            remember(
                (claimOf(id) ?: Claim(id = id, claimedAt = System.currentTimeMillis(), label = label)).copy(
                    label = app,
                    packageName = packageName,
                    versionName = versionName,
                    previousVersionName = installedInfo(packageName)?.versionName,
                ),
            )

            if (!pm.canRequestPackageInstalls()) {
                _canInstall.value = false
                remember(claimOf(id)!!.copy(waitingForPermission = true))
                report(
                    id,
                    PhoneInstallStatus(
                        PhoneInstallState.NEEDS_ACTION,
                        message = "TGXCode is not allowed to install apps yet. On the phone, " +
                            "turn on Settings → Apps → TGXCode → Install unknown apps; " +
                            "the install carries on by itself after that.",
                        packageName = packageName,
                        versionName = versionName,
                    ),
                )
                InstallNotifications.showNeedsPermission(context, app)
                Tgx.d("install $id: waiting for the unknown-apps grant")
                return
            }

            commit(id, file, app, packageName, versionName)
        } catch (cancelled: CancellationException) {
            Tgx.d("install $id: cancelled")
            file.delete()
            forget(id)
            throw cancelled
        }
    }

    /** Pick back up every install that was only waiting for the grant. */
    private suspend fun resumeWaiting() {
        val waiting = settings.installClaims().filter { it.waitingForPermission }
        if (waiting.isEmpty()) return
        val installs = api.phoneInstalls().getOrNull()?.installs ?: return
        for (claim in waiting) {
            val record = installs.firstOrNull { it.id == claim.id }
            if (record == null || record.state != PhoneInstallState.NEEDS_ACTION) {
                forget(claim.id)
                continue
            }
            remember(claim.copy(waitingForPermission = false))
            enqueue(record, claimed = true)
        }
    }

    /**
     * Hand it to PackageInstaller. `installing` goes to the bridge *first*: if this is
     * an update to TGXCode itself, the commit is the last thing this process does.
     */
    private suspend fun commit(
        id: String,
        file: File,
        app: String,
        packageName: String,
        versionName: String?,
    ) {
        report(id, PhoneInstallStatus(PhoneInstallState.INSTALLING, packageName = packageName, versionName = versionName))
        Tgx.d("install $id: committing $packageName $versionName" + if (packageName == context.packageName) " (self-update)" else "")

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // Keeps the next update silent: with TGXCode as update owner, nothing else
            // can quietly take the package over between one build and the next.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
        var sessionId = -1
        try {
            withContext(Dispatchers.IO) {
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            input.copyTo(output, 64 * 1024)
                            session.fsync(output)
                        }
                    }
                    val result = Intent(context, InstallResultReceiver::class.java)
                        .setAction(InstallResultReceiver.ACTION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(InstallResultReceiver.EXTRA_INSTALL_ID, id)
                        .putExtra(InstallResultReceiver.EXTRA_APP, app)
                        .putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
                        .putExtra(InstallResultReceiver.EXTRA_VERSION, versionName)
                    // Mutable, because PackageInstaller writes the status into it. The
                    // intent is explicit, which is what makes that safe.
                    val pending = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        result,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                }
            }
            // PackageInstaller has its own copy now.
            file.delete()
        } catch (cancelled: CancellationException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            throw cancelled
        } catch (t: Throwable) {
            Tgx.w("install $id: could not start the install session", t)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            fail(id, app, "Could not start the install: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------- results

    /** From [InstallResultReceiver]: PackageInstaller's answer for one session. */
    suspend fun onResult(intent: Intent) {
        val id = intent.getStringExtra(InstallResultReceiver.EXTRA_INSTALL_ID) ?: return
        val app = intent.getStringExtra(InstallResultReceiver.EXTRA_APP) ?: "the app"
        val packageName = intent.getStringExtra(InstallResultReceiver.EXTRA_PACKAGE)
        val versionName = intent.getStringExtra(InstallResultReceiver.EXTRA_VERSION)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val platformMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val outcome = PhoneInstallRules.outcomeOf(status, platformMessage, app)
        Tgx.d("install $id: status $status ($platformMessage) -> ${outcome.state}")

        when (outcome.state) {
            PhoneInstallState.NEEDS_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null && !(foreground && launch(confirm))) {
                    InstallNotifications.showConfirm(context, id, app, confirm)
                }
                report(id, PhoneInstallStatus(outcome.state, outcome.message, packageName, versionName))
            }
            PhoneInstallState.INSTALLED -> {
                InstallNotifications.showInstalled(context, id, app, versionName, packageName)
                report(id, PhoneInstallStatus(outcome.state, null, packageName, versionName))
                forget(id)
            }
            else -> {
                InstallNotifications.showFailed(context, id, app, outcome.message.orEmpty())
                report(id, PhoneInstallStatus(outcome.state, outcome.message, packageName, versionName))
                forget(id)
            }
        }
    }

    /** Only while on screen; from the background Android refuses, and says nothing. */
    private fun launch(confirm: Intent): Boolean = runCatching {
        context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * Settle what the persisted claims say against what the bridge says, after a
     * restart in particular. A record this process is still working on is left alone.
     */
    private suspend fun reconcile(installs: List<PhoneInstall>) {
        val claims = settings.installClaims()
        if (claims.isEmpty()) return
        val inHand = synchronized(activeLock) { active.keys.toSet() }
        for (claim in claims) {
            if (claim.id in inHand) continue
            val record = installs.firstOrNull { it.id == claim.id }
            if (record == null || record.state in PhoneInstallState.FINAL || record.state == PhoneInstallState.FAILED) {
                forget(claim.id)
                continue
            }
            val info = claim.packageName?.let(::installedInfo)
            val landed = info != null && PhoneInstallRules.installLanded(claim, info.versionName, info.lastUpdateTime)
            when {
                // The update went on while nobody was listening — TGXCode's own update
                // above all, which killed the process that committed it.
                record.state in IN_FLIGHT && landed -> {
                    Tgx.d("install ${claim.id}: ${claim.packageName} is on — reporting installed")
                    InstallNotifications.showInstalled(context, claim.id, claim.label, info.versionName, claim.packageName)
                    report(
                        claim.id,
                        PhoneInstallStatus(PhoneInstallState.INSTALLED, null, claim.packageName, info.versionName),
                    )
                    forget(claim.id)
                }
                // Granted from the system settings without coming back through the app.
                claim.waitingForPermission && record.state == PhoneInstallState.NEEDS_ACTION &&
                    enabled.value && pm.canRequestPackageInstalls() -> {
                    _canInstall.value = true
                    InstallNotifications.cancelPermission(context)
                    remember(claim.copy(waitingForPermission = false))
                    enqueue(record, claimed = true)
                }
                // The process died part-way through the download; it is still ours.
                record.state == PhoneInstallState.DOWNLOADING && enabled.value -> enqueue(record, claimed = true)
                record.state == PhoneInstallState.INSTALLING &&
                    System.currentTimeMillis() - claim.claimedAt > LOST_AFTER_MILLIS -> {
                    val message = "Lost track of this install after TGXCode restarted — send it again."
                    report(claim.id, PhoneInstallStatus(PhoneInstallState.FAILED, message))
                    forget(claim.id)
                }
                // `needs_action` waits on a tap or a grant, as long as that takes.
                else -> Unit
            }
        }
    }

    private suspend fun fail(id: String, app: String, message: String) {
        Tgx.w("install $id failed: $message")
        apkFile(id).delete()
        InstallNotifications.showFailed(context, id, app, message)
        report(id, PhoneInstallStatus(PhoneInstallState.FAILED, message))
        forget(id)
    }

    private suspend fun report(id: String, status: PhoneInstallStatus): ApiResult<*> =
        api.phoneInstallStatus(id, status, settings.installViewerId()).also {
            if (it !is ApiResult.Ok && !it.isConflict) Tgx.w("install $id: reporting ${status.state} failed: ${it.message()}")
        }

    // ------------------------------------------------------------- claims

    private suspend fun claimOf(id: String): Claim? = settings.installClaims().firstOrNull { it.id == id }

    private suspend fun remember(claim: Claim) =
        settings.updateInstallClaims { claims -> claims.filterNot { it.id == claim.id } + claim }

    private suspend fun forget(id: String) =
        settings.updateInstallClaims { claims -> claims.filterNot { it.id == id } }

    // ------------------------------------------------------------- packages

    private fun installedInfo(packageName: String): PackageInfo? = try {
        pm.getPackageInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** The app's own name from inside the APK — what the person will recognise. */
    private fun appName(archive: PackageInfo, file: File): String? = runCatching {
        val info = archive.applicationInfo ?: return null
        info.sourceDir = file.path
        info.publicSourceDir = file.path
        info.loadLabel(pm).toString().takeIf { it.isNotBlank() && it != archive.packageName }
    }.getOrNull()

    private suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val IN_FLIGHT = setOf(PhoneInstallState.INSTALLING, PhoneInstallState.NEEDS_ACTION)

        /** An `installing` this old, with nothing on the phone to show for it, is not coming. */
        private const val LOST_AFTER_MILLIS = 30 * 60 * 1000L
    }
}
