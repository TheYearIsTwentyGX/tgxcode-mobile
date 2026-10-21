package com.twentygx.tgxcode.data.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.isConflict
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallProgress
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.settings.SettingsStore
import com.twentygx.tgxcode.service.InstallNotifications
import com.twentygx.tgxcode.service.InstallResultReceiver
import com.twentygx.tgxcode.service.InstallService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * Hangs off [BridgeRepository]'s fan-out like the other stores, so it hears about work
 * over whatever connection is already open — the UI's, or the background service's.
 * The work itself runs on the app-wide scope, never the connection's, so a reconnect
 * (the bridge restarting, a cell handoff) does not touch a download in flight; and
 * while there is any, [InstallService] holds the process in the foreground and a wake
 * lock keeps the CPU up, so it finishes in the background with the screen off.
 * Installs run one at a time as far as the commit, so two downloads never fight over a
 * weak link; a confirmation still waiting on a tap does not hold up the next one.
 *
 * **Never silent.** A claim that goes quiet is the failure this is built against:
 * - the download sends a `progress` heartbeat every ~2s, which is how the desk tells
 *   slow from dead, and what keeps the bridge from releasing a slow one;
 * - a download that stalls for a minute fails that attempt, and each install gets
 *   three attempts, resumed with `Range`, before it is reported `failed`;
 * - any exception on the way is reported `failed` with its message;
 * - a report that does not get through is kept in the [Claim]'s outbox and sent again
 *   on the next catch-up, rather than dropped with the claim;
 * - the claim is persisted *before* it is posted, and catch-up resumes this phone's
 *   own `downloading` records as well as `pending` ones, so a lost answer or a dead
 *   process is picked back up instead of left claimed for good.
 *
 * The bridge releases a claim after three quiet minutes and fails it after three
 * claims, so a record can stop being this phone's while it works; [onRecord] stops the
 * work when the stream says so.
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

    /** One record being worked on in this process, from enqueue to commit. */
    private class InHand {
        lateinit var job: Job

        /** Set once the bridge has this phone's claim (or it was ours already). */
        @Volatile var claimed = false

        /** The bridge's `updatedAt` as of the claim: anything older is not news. */
        @Volatile var since = 0L

        /** Why it was stopped from outside, when it was. */
        @Volatile var abandoned: String? = null
    }

    private val active = mutableMapOf<String, InHand>()
    private val activeLock = Any()

    /** One at a time, up to the commit — see the class comment. */
    private val serial = Mutex()

    /** The stream, the poll and the start can all ask for a catch-up at once. */
    private val catchingUp = Mutex()

    private val _records = MutableStateFlow<List<PhoneInstall>?>(null)

    /** The queue as the bridge last told it, newest first. Null until first read. */
    val records: StateFlow<List<PhoneInstall>?> = _records.asStateFlow()

    private val _work = MutableStateFlow<InstallWork?>(null)

    /** The install running now — the ongoing notification's content. */
    val work: StateFlow<InstallWork?> = _work.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** Anything in hand, queued or running: what [InstallService] stays up for. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Keeps the CPU up with the screen off; the service only keeps the process. */
    private val wakeLock: PowerManager.WakeLock? by lazy {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGXCode:phone-install")
            ?.apply { setReferenceCounted(false) }
    }

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
        _records.update { list -> list?.let { upserted(it, record) } }
        val held = synchronized(activeLock) { active[record.id] }
        if (held != null && PhoneInstallRules.noLongerOurs(record, held.claimed, held.since)) {
            abandon(record, held)
            return
        }
        when (record.state) {
            PhoneInstallState.PENDING -> enqueue(record, claimed = false)
            // Taken back at the desk while waiting on the grant: nothing is running,
            // so there is only the claim and the file to clear up.
            PhoneInstallState.CANCELLED -> {
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
     * Stop work the bridge says is no longer this phone's. A session already committed
     * is Android's now and finishes on its own.
     */
    private fun abandon(record: PhoneInstall, held: InHand) {
        if (held.abandoned != null) return
        Tgx.d("install ${record.id}: ${record.state} on the desk — stopping the work in hand")
        held.abandoned = record.state
        held.job.cancel()
        // Released, so anybody's to take — this phone's too. Pick it back up once the
        // old work has let go; the partial file makes it a resume.
        if (record.state == PhoneInstallState.PENDING) {
            scope.launch {
                held.job.join()
                enqueue(record, claimed = false)
            }
        }
    }

    /**
     * Read the queue and pick up anything missed while away. Nothing is replayed to a
     * client that was not connected, so a `pending` filed then is only ever found here
     * — and so is a record this phone claimed and then lost hold of.
     */
    private suspend fun catchUp() {
        catchingUp.withLock {
            val installs = api.phoneInstalls().getOrNull()?.installs ?: return
            _records.value = installs
            // What the poll says about work in hand, for when the stream is not
            // carrying events.
            for (record in installs) {
                val held = synchronized(activeLock) { active[record.id] } ?: continue
                if (PhoneInstallRules.noLongerOurs(record, held.claimed, held.since)) abandon(record, held)
            }
            reconcile(installs)
            if (!enabled.value) return
            val inHand = synchronized(activeLock) { active.keys.toSet() }
            val own = settings.installClaims()
                .filter { it.outbox == null && !it.waitingForPermission }
                .map { it.id }
                .toSet()
            PhoneInstallRules.toPickUp(installs, own, inHand).forEach { enqueue(it, claimed = false) }
        }
    }

    /** The Installs screen, on open and on pull: read the queue now. */
    suspend fun refresh() = catchUp()

    /**
     * The Installs screen's Retry: the bridge puts the record back to `pending`, and
     * its broadcast is what starts the work. Null when it took it, else what to say.
     */
    suspend fun retry(id: String): String? {
        val result = api.retryPhoneInstall(id)
        return when {
            result is ApiResult.Ok -> {
                catchUp()
                null
            }
            result is ApiResult.Http && result.status == 410 ->
                "The APK is no longer on the desktop — send it again from there."
            result.isConflict -> "That one is already installed."
            else -> "Couldn't retry: ${result.message()}"
        }
    }

    private fun enqueue(record: PhoneInstall, claimed: Boolean) {
        if (!enabled.value) return
        synchronized(activeLock) {
            if (record.id in active) return
            val held = InHand()
            held.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    serial.withLock { run(record, claimed, held) }
                } finally {
                    synchronized(activeLock) { active.remove(record.id) }
                    activeChanged()
                }
            }
            active[record.id] = held
            held.job.start()
        }
        activeChanged()
    }

    /** Keep [busy] current, and bring [InstallService] up as the queue fills. */
    private fun activeChanged() {
        val rising = synchronized(activeLock) {
            val busy = active.isNotEmpty()
            val rising = busy && !_busy.value
            _busy.value = busy
            rising
        }
        if (rising) InstallService.start(context)
    }

    // ------------------------------------------------------------- the work

    private fun apkFile(id: String) = File(context.cacheDir, "phone-installs/$id.apk")

    private suspend fun run(record: PhoneInstall, claimed: Boolean, held: InHand) {
        val id = record.id
        val label = record.label.ifBlank { record.fileName.ifBlank { "the app" } }
        val file = apkFile(id)
        held.since = record.updatedAt
        wakeLock?.acquire(WAKE_MILLIS)
        try {
            if (!claimed) {
                // Persisted *before* it is posted. If the bridge takes the claim and the
                // answer is lost on the way back, the record says `downloading` on the
                // desk, and this claim is the only thing that says it is ours to resume.
                // A `downloading` record of ours is claimed the same way: the bridge
                // takes it as a heartbeat, and a 409 means it went to someone else.
                remember(
                    (claimOf(id) ?: Claim(id = id, claimedAt = System.currentTimeMillis(), label = label))
                        .copy(outbox = null),
                )
                val claim = report(id, PhoneInstallStatus(PhoneInstallState.DOWNLOADING))
                if (claim.isConflict) {
                    Tgx.d("install $id: claimed elsewhere — dropping it")
                    forget(id)
                    return
                }
                if (claim !is ApiResult.Ok) {
                    // Kept: the next catch-up claims it again if it is still pending, or
                    // resumes it if the claim landed after all.
                    Tgx.w("install $id: could not claim it: ${claim.message()}")
                    return
                }
                held.since = maxOf(held.since, updatedAtOf(claim.value))
            }
            held.claimed = true
            Tgx.d("install $id: downloading ${record.fileName} (${record.size} bytes)")
            show(InstallWork(id, label, PhoneInstallState.DOWNLOADING, 0, record.size))

            if (!alreadyHere(record, file)) {
                val wrong = download(record, label, file, held)
                if (wrong != null) return fail(id, label, wrong, held)
            }

            val archive = withContext(Dispatchers.IO) { pm.getPackageArchiveInfo(file.path, 0) }
                ?: return fail(id, label, "Android could not read ${record.fileName} as an APK.", held)
            val packageName = archive.packageName
            val versionName = archive.versionName
            val app = appName(archive, file) ?: label
            val kept = (claimOf(id) ?: Claim(id = id, claimedAt = System.currentTimeMillis(), label = label)).copy(
                label = app,
                packageName = packageName,
                versionName = versionName,
                previousVersionName = installedInfo(packageName)?.versionName,
            )
            remember(kept)

            if (!pm.canRequestPackageInstalls()) {
                _canInstall.value = false
                remember(kept.copy(waitingForPermission = true))
                held.claimed = false
                deliver(
                    id,
                    PhoneInstallStatus(
                        PhoneInstallState.NEEDS_ACTION,
                        message = "TGXCode is not allowed to install apps yet. On the phone, " +
                            "turn on Settings → Apps → TGXCode → Install unknown apps; " +
                            "the install carries on by itself after that.",
                        packageName = packageName,
                        versionName = versionName,
                    ),
                    done = false,
                )
                InstallNotifications.showNeedsPermission(context, app)
                Tgx.d("install $id: waiting for the unknown-apps grant")
                return
            }

            commit(id, file, app, packageName, versionName, held)
        } catch (cancelled: CancellationException) {
            // Released to `pending`: keep the partial file, since this phone may well
            // be the one to pick it back up.
            val keep = held.abandoned == PhoneInstallState.PENDING
            Tgx.d("install $id: stopped" + (held.abandoned?.let { " ($it on the desk)" } ?: ""))
            withContext(NonCancellable) {
                if (!keep) file.delete()
                runCatching { forget(id) }
            }
            throw cancelled
        } catch (t: Throwable) {
            // Whatever it was, the desk hears about it. An exception that got out of
            // here used to leave the record `downloading` for good — and on the app
            // scope, with no handler, take the process down with it.
            Tgx.w("install $id: stopped by ${t.javaClass.simpleName}", t)
            fail(id, label, "The install stopped on the phone: ${t.message ?: t.javaClass.simpleName}", held)
        } finally {
            _work.value = null
            InstallNotifications.cancelProgress(context)
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    /** A file from an earlier attempt that is already the whole, right APK. */
    private suspend fun alreadyHere(record: PhoneInstall, file: File): Boolean =
        file.exists() &&
            (record.size <= 0 || file.length() == record.size) &&
            PhoneInstallRules.verify(record, file.length(), sha256Of(file)) == null

    /**
     * Fetch the APK: up to [ATTEMPTS] tries, each resuming from whatever the last one
     * left on disk, with a `progress` heartbeat throughout. Null once the file is here
     * and checks out; else the sentence for the record's message.
     */
    private suspend fun download(record: PhoneInstall, label: String, file: File, held: InHand): String? =
        coroutineScope {
            val id = record.id
            // Posted from their own coroutine, so a slow post never holds up the bytes
            // and only the newest progress waits to go.
            val beat = MutableStateFlow<PhoneInstallProgress?>(null)
            val heartbeat = launch {
                beat.filterNotNull().collect { progress ->
                    val sent = report(id, PhoneInstallStatus(PhoneInstallState.DOWNLOADING, progress = progress))
                    if (sent.isConflict && held.abandoned == null) {
                        // Released while this phone was quiet, and claimed elsewhere.
                        Tgx.d("install $id: another phone holds it now — stopping")
                        held.abandoned = "taken"
                        held.job.cancel()
                    }
                }
            }
            try {
                var failure: String? = null
                for (attempt in 1..ATTEMPTS) {
                    if (attempt > 1) {
                        Tgx.w("install $id: attempt ${attempt - 1} of $ATTEMPTS failed ($failure) — trying again")
                        delay(RETRY_DELAYS_MILLIS[attempt - 2])
                    }
                    wakeLock?.acquire(WAKE_MILLIS)
                    val note = if (attempt > 1) "Attempt $attempt of $ATTEMPTS" else null
                    val throttle = ProgressThrottle()
                    val from = PhoneInstallRules.resumeFrom(if (file.exists()) file.length() else 0, record.size)
                    val result = api.downloadPhoneInstall(id, file, from, record.sha256) { bytes, total ->
                        if (throttle.shouldSend(bytes, total, System.currentTimeMillis())) {
                            beat.value = PhoneInstallProgress(bytes, total)
                            show(InstallWork(id, label, PhoneInstallState.DOWNLOADING, bytes, total, note))
                        }
                    }
                    failure = when (result) {
                        is ApiResult.Ok -> {
                            val got = result.value
                            val wrong = PhoneInstallRules.verify(record, got.size, got.sha256, got.headerSha256)
                                ?: return@coroutineScope null
                            file.delete()
                            // A whole file that is wrong will be wrong again. One that was
                            // resumed may have a bad start, which a clean try settles.
                            if (got.resumedFrom == 0L) return@coroutineScope wrong
                            wrong
                        }
                        // Installed, cancelled or expired on the desk: nothing left to retry.
                        is ApiResult.Http if result.status == 410 ->
                            return@coroutineScope "The APK is no longer on the desktop (installed, cancelled or expired)."
                        is ApiResult.Http if result.status == 404 ->
                            return@coroutineScope "The desktop no longer has this install."
                        is ApiResult.AccessDenied -> return@coroutineScope "Download failed: ${result.message()}"
                        else -> "Download failed: ${result.message()}"
                    }
                }
                failure
            } finally {
                heartbeat.cancel()
            }
        }

    private fun show(work: InstallWork) {
        _work.value = work
        InstallNotifications.showProgress(context, work)
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
        held: InHand,
    ) {
        show(InstallWork(id, app, PhoneInstallState.INSTALLING))
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
            fail(id, app, "Could not start the install: ${t.message ?: t.javaClass.simpleName}", held)
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
                deliver(id, PhoneInstallStatus(outcome.state, outcome.message, packageName, versionName), done = false)
            }
            PhoneInstallState.INSTALLED -> {
                InstallNotifications.showInstalled(context, id, app, versionName, packageName)
                deliver(id, PhoneInstallStatus(outcome.state, null, packageName, versionName), done = true)
            }
            else -> {
                InstallNotifications.showFailed(context, id, app, outcome.message.orEmpty())
                deliver(id, PhoneInstallStatus(outcome.state, outcome.message, packageName, versionName), done = true)
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
     * A `downloading` claim is not resumed here: [PhoneInstallRules.toPickUp] takes it.
     */
    private suspend fun reconcile(installs: List<PhoneInstall>) {
        val claims = settings.installClaims()
        if (claims.isEmpty()) return
        val inHand = synchronized(activeLock) { active.keys.toSet() }
        for (claim in claims) {
            if (claim.id in inHand) continue
            val record = installs.firstOrNull { it.id == claim.id }
            // A report that never got through. Sent again unless the record has moved
            // on without it: settled, released to `pending`, or already saying it.
            val unsent = claim.outbox
            if (unsent != null) {
                val moot = record == null || record.state in PhoneInstallState.FINAL ||
                    record.state == PhoneInstallState.PENDING || record.state == unsent.state
                val keepClaim = unsent.state == PhoneInstallState.NEEDS_ACTION && record?.state == unsent.state
                when {
                    keepClaim -> remember(claim.copy(outbox = null))
                    moot -> forget(claim.id)
                    else -> {
                        Tgx.d("install ${claim.id}: sending the ${unsent.state} that did not get through")
                        deliver(claim.id, unsent, done = unsent.state != PhoneInstallState.NEEDS_ACTION)
                    }
                }
                continue
            }
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
                    deliver(
                        claim.id,
                        PhoneInstallStatus(PhoneInstallState.INSTALLED, null, claim.packageName, info.versionName),
                        done = true,
                    )
                }
                // Granted from the system settings without coming back through the app.
                claim.waitingForPermission && record.state == PhoneInstallState.NEEDS_ACTION &&
                    enabled.value && pm.canRequestPackageInstalls() -> {
                    _canInstall.value = true
                    InstallNotifications.cancelPermission(context)
                    remember(claim.copy(waitingForPermission = false))
                    enqueue(record, claimed = true)
                }
                record.state == PhoneInstallState.INSTALLING &&
                    System.currentTimeMillis() - claim.claimedAt > LOST_AFTER_MILLIS -> {
                    val message = "Lost track of this install after TGXCode restarted — send it again."
                    deliver(claim.id, PhoneInstallStatus(PhoneInstallState.FAILED, message), done = true)
                }
                // `needs_action` waits on a tap or a grant, as long as that takes.
                else -> Unit
            }
        }
    }

    private suspend fun fail(id: String, app: String, message: String, held: InHand? = null) {
        // Our own `failed`, coming back on the stream, is not news about this work.
        held?.claimed = false
        Tgx.w("install $id failed: $message")
        apkFile(id).delete()
        InstallNotifications.showFailed(context, id, app, message)
        deliver(id, PhoneInstallStatus(PhoneInstallState.FAILED, message), done = true)
    }

    /**
     * Report a state the bridge has to hear. It sits in the claim's outbox until it
     * has, so one made while the bridge is down — a download cut off by the bridge
     * restarting fails at exactly that moment — is sent again on the next catch-up
     * instead of being lost with the claim. [done]: the claim is finished with once
     * the report lands.
     */
    private suspend fun deliver(id: String, status: PhoneInstallStatus, done: Boolean) {
        runCatching {
            settings.updateInstallClaims { claims ->
                val claim = claims.firstOrNull { it.id == id } ?: Claim(id = id, claimedAt = System.currentTimeMillis())
                claims.filterNot { it.id == id } + claim.copy(outbox = status)
            }
        }.onFailure { Tgx.w("install $id: could not keep the ${status.state} report", it) }
        val result = report(id, status)
        // A 409 or 404 is an answer too: there is nothing left to say it to.
        val landed = result is ApiResult.Ok || result.isConflict || (result is ApiResult.Http && result.status == 404)
        runCatching {
            when {
                !landed -> Tgx.w("install $id: ${status.state} not delivered — kept to send again")
                done -> forget(id)
                else -> settings.updateInstallClaims { claims ->
                    claims.map { if (it.id == id) it.copy(outbox = null) else it }
                }
            }
        }
    }

    private suspend fun report(id: String, status: PhoneInstallStatus): ApiResult<JsonObject> =
        api.phoneInstallStatus(id, status, settings.installViewerId()).also {
            if (it !is ApiResult.Ok && !it.isConflict) Tgx.w("install $id: reporting ${status.state} failed: ${it.message()}")
        }

    private fun updatedAtOf(record: JsonObject): Long =
        runCatching { record["updatedAt"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L

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

        /** Tries per install before `failed`: the first, and two more. */
        private const val ATTEMPTS = 3

        /** Long enough for a restarting bridge to come back. */
        private val RETRY_DELAYS_MILLIS = longArrayOf(5_000, 15_000)

        /** A bound on the wake lock should anything fail to release it; renewed per attempt. */
        private const val WAKE_MILLIS = 30 * 60 * 1000L

        /** [record] in place of its old copy, newest first. */
        internal fun upserted(list: List<PhoneInstall>, record: PhoneInstall): List<PhoneInstall> =
            (list.filterNot { it.id == record.id } + record).sortedByDescending { it.createdAt }

        /** An `installing` this old, with nothing on the phone to show for it, is not coming. */
        private const val LOST_AFTER_MILLIS = 30 * 60 * 1000L
    }
}

/** The install this phone is running now: what the ongoing notification shows. */
data class InstallWork(
    val id: String,
    val app: String,
    /** `downloading` or `installing`. */
    val phase: String,
    val bytes: Long = 0,
    /** 0 when the length is not known. */
    val total: Long = 0,
    /** A retry, say, worth a word under the title. */
    val note: String? = null,
)
