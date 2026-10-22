package com.twentygx.tgxcode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.twentygx.tgxcode.data.background.BackgroundIntents
import com.twentygx.tgxcode.data.background.BackgroundRules
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.repo.NavCommand
import com.twentygx.tgxcode.data.repo.appliesIn
import com.twentygx.tgxcode.data.repo.isRefused
import com.twentygx.tgxcode.ui.common.ConnectionDot
import com.twentygx.tgxcode.ui.common.DegradedBanner
import com.twentygx.tgxcode.ui.common.RemoteBanner
import com.twentygx.tgxcode.ui.conversation.ComposerControls
import com.twentygx.tgxcode.ui.conversation.ChangesSheet
import com.twentygx.tgxcode.ui.conversation.ConversationScreen
import com.twentygx.tgxcode.ui.conversation.LaterSheet
import com.twentygx.tgxcode.ui.conversation.ScheduleSendDialog
import com.twentygx.tgxcode.ui.conversation.SubagentScreen
import com.twentygx.tgxcode.ui.conversation.UserTurnActions
import com.twentygx.tgxcode.ui.newsession.NewSessionScreen
import com.twentygx.tgxcode.ui.orchestrator.OrchestratorActions
import com.twentygx.tgxcode.ui.orchestrator.OrchestratorPickerSheet
import com.twentygx.tgxcode.ui.orchestrator.OrchestratorScreen
import com.twentygx.tgxcode.ui.orchestrator.OrchestratorSettingsSheet
import com.twentygx.tgxcode.ui.orchestrator.OrchestratorTabs
import com.twentygx.tgxcode.ui.orchestrator.WorkerBanner
import com.twentygx.tgxcode.ui.orchestrator.needsCount
import com.twentygx.tgxcode.ui.orchestrator.needsYou
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.ui.newsession.recentDirs
import com.twentygx.tgxcode.ui.sessions.PrSheet
import com.twentygx.tgxcode.ui.sessions.SessionActionsSheet
import com.twentygx.tgxcode.ui.sessions.SessionSheetActions
import com.twentygx.tgxcode.ui.sessions.SessionsScreen
import com.twentygx.tgxcode.ui.tasks.TaskActions
import com.twentygx.tgxcode.ui.tasks.TasksScreen
import com.twentygx.tgxcode.ui.settings.AccessDeniedScreen
import com.twentygx.tgxcode.ui.settings.NotPairedScreen
import com.twentygx.tgxcode.ui.settings.SettingsScreen
import com.twentygx.tgxcode.ui.installs.InstallsScreen
import com.twentygx.tgxcode.ui.schedules.SchedulesScreen
import com.twentygx.tgxcode.ui.snippets.SnippetsScreen
import com.twentygx.tgxcode.ui.status.NotificationsScreen
import com.twentygx.tgxcode.ui.status.QuotaPill
import com.twentygx.tgxcode.ui.status.QuotaSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import kotlinx.serialization.Serializable

/** Mirrors the web client's hash routes, so notifications can deep-link the same way. */
@Serializable object Sessions
@Serializable object Tasks
@Serializable object NewSession
@Serializable object Settings
@Serializable object Notifications
@Serializable object Schedules
@Serializable object Snippets
@Serializable object Installs
@Serializable data class Conversation(val sessionId: String)
@Serializable data class Subagent(val sessionId: String, val toolUseId: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TgxCodeRoot(
    vm: AppViewModel,
    deepLinkSessionId: String? = null,
    pairingLink: String? = null,
    openInstalls: Boolean = false,
    onDeepLinkHandled: () -> Unit = {},
    onInstallsHandled: () -> Unit = {},
    onPairingHandled: () -> Unit = {},
    onStayConnected: (Boolean) -> Unit = {},
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // The Settings card's buttons. Most open a system screen; coming back is a resume,
    // which re-reads what they changed.
    val onBackgroundFix: (BackgroundRules.Fix) -> Unit = { fix ->
        when (fix) {
            BackgroundRules.Fix.StayConnected -> onStayConnected(true)
            BackgroundRules.Fix.Reconnect -> vm.background.reconnectNow()
            BackgroundRules.Fix.BatteryExempt -> BackgroundIntents.openBatteryExemption(context)
            BackgroundRules.Fix.InstallPermission -> vm.grantInstallPermission()
            BackgroundRules.Fix.Notifications -> BackgroundIntents.openNotifications(context)
            BackgroundRules.Fix.CheckNow -> vm.background.checkNow()
        }
    }

    val connection by vm.repo.connection.collectAsStateWithLifecycle()
    val health by vm.repo.health.collectAsStateWithLifecycle()
    val board by vm.repo.board.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val conversation by vm.repo.conversation.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val staged by vm.staged.collectAsStateWithLifecycle()
    val answering by vm.answering.collectAsStateWithLifecycle()
    val settingsUi by vm.settingsUi.collectAsStateWithLifecycle()
    val newSession by vm.newSession.collectAsStateWithLifecycle()
    val tasks by vm.repo.tasks.collectAsStateWithLifecycle()
    val taskFilter by vm.repo.taskFilter.collectAsStateWithLifecycle()
    val openTasks by vm.repo.openTasks.collectAsStateWithLifecycle()
    val drafts by vm.repo.drafts.collectAsStateWithLifecycle()
    val prs by vm.repo.prs.collectAsStateWithLifecycle()
    val prSheet by vm.prSheet.collectAsStateWithLifecycle()
    val acting by vm.acting.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val opening by vm.repo.opening.collectAsStateWithLifecycle()
    val sessionSheet by vm.sessionSheet.collectAsStateWithLifecycle()
    val branch by vm.branch.collectAsStateWithLifecycle()
    val quota by vm.status.quota.collectAsStateWithLifecycle()
    val refreshingQuota by vm.status.refreshingQuota.collectAsStateWithLifecycle()
    val inbox by vm.status.inbox.collectAsStateWithLifecycle()
    val projectColors by vm.status.projectColors.collectAsStateWithLifecycle()
    var quotaOpen by rememberSaveable { mutableStateOf(false) }
    var turnsOpen by rememberSaveable { mutableStateOf(false) }
    val agentView by vm.repo.agentView.collectAsStateWithLifecycle()
    val later by vm.workspace.later.collectAsStateWithLifecycle()
    val snippets by vm.workspace.snippets.collectAsStateWithLifecycle()
    val slash by vm.slash.collectAsStateWithLifecycle()
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var laterOpen by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<com.twentygx.tgxcode.data.model.LaterMessage?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    val orchPayloads by vm.orchestrators.payloads.collectAsStateWithLifecycle()
    val orchList by vm.orchestrators.orchestrators.collectAsStateWithLifecycle()
    val orchBusy by vm.orchestrators.busy.collectAsStateWithLifecycle()
    val restartConfirm by vm.restartConfirm.collectAsStateWithLifecycle()
    var orchSettingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<PickTarget?>(null) }

    var filter by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.repo.messages.collect { message ->
            snackbar.showSnackbar(
                message = message.text,
                // An error you cannot finish reading is not much better than no
                // error, and these carry the reason a send or an answer failed.
                withDismissAction = message.isError,
                duration = if (message.isError) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
        }
    }
    LaunchedEffect(Unit) {
        vm.repo.commands.collect { command ->
            when (command) {
                is NavCommand.Follow -> nav.navigate(Conversation(command.sessionId)) {
                    popUpTo(Conversation(command.from())) { inclusive = true }
                }
                is NavCommand.Deleted -> nav.navigate(Sessions) {
                    popUpTo(Sessions) { inclusive = true }
                }
            }
        }
    }
    LaunchedEffect(deepLinkSessionId) {
        deepLinkSessionId?.let {
            nav.navigate(Conversation(it))
            onDeepLinkHandled()
        }
    }

    // From an install notification: what happened to the APK the desktop sent.
    LaunchedEffect(openInstalls) {
        if (openInstalls) {
            nav.navigate(Installs) { launchSingleTop = true }
            onInstallsHandled()
        }
    }

    // A pairing link is filled into the form and the form is shown — never applied
    // silently. The origin is what decides where a token gets sent, so the user
    // should see it before it is trusted.
    LaunchedEffect(pairingLink) {
        pairingLink?.let {
            vm.onPaste(it)
            nav.navigate(Settings)
            onPairingHandled()
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onConversation = destination?.hasRoute(Conversation::class) == true
    val onSettings = destination?.hasRoute(Settings::class) == true
    val onNotifications = destination?.hasRoute(Notifications::class) == true
    val onSubagent = destination?.hasRoute(Subagent::class) == true
    val onSchedules = destination?.hasRoute(Schedules::class) == true
    val onSnippets = destination?.hasRoute(Snippets::class) == true
    val onInstalls = destination?.hasRoute(Installs::class) == true
    val onTasks = destination?.hasRoute(Tasks::class) == true
    // Hidden on a conversation, exactly like mobile.css hides the tab bar there —
    // one screen at a time is the whole shape of this surface.
    val pushed = onConversation || onSettings || onNotifications || onSubagent || onSchedules || onSnippets ||
        onInstalls
    val showTabs = !pushed

    val title = when {
        onConversation -> conversation?.summary?.displayTitle
            ?: if (opening != null) "Starting…" else "Loading…"
        onSettings -> "Settings"
        onNotifications -> "History"
        onSubagent -> agentView?.title ?: "Subagent"
        onSchedules -> "Schedules"
        onSnippets -> "Snippets"
        onInstalls -> "Phone installs"
        destination?.hasRoute(Tasks::class) == true -> "Tasks"
        destination?.hasRoute(NewSession::class) == true -> "Start a session"
        else -> "Sessions"
    }

    // One set of actions for both places an offer is drawn — the Tasks tab and inline
    // in a conversation — so the two cannot drift.
    val taskActions = remember(nav) {
        TaskActions(
            onStart = { task -> vm.startTask(task) { id -> nav.navigate(Conversation(id)) } },
            onDismiss = vm::dismissTask,
            onUndo = vm::undoTask,
            onOpenSession = { nav.navigate(Conversation(it)) },
        )
    }

    /**
     * Open a pull request in whatever handles links.
     *
     * The only external URL this app opens. `LocalUriHandler` is `startActivity` with
     * an `ACTION_VIEW`, which needs no manifest change and no `<queries>` element —
     * Android 11's package visibility does not apply, because nothing here resolves
     * the intent itself. It does throw when there is no browser at all, so the failure
     * lands on the snackbar rather than on the user.
     */
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }.onFailure { vm.reportUnopenableUrl(url) }
    }

    val askingSessions = board?.sessions.orEmpty()
        .filter { it.ask != null }
        .map { it.sessionId }
        .toSet()

    // The desktop's project colours, for any row that wants to wear one.
    androidx.compose.runtime.CompositionLocalProvider(
        com.twentygx.tgxcode.ui.theme.LocalProjectColors provides projectColors,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                val remote = health?.remote == true
                // Degraded outranks remote rather than stacking with it: polling only
                // happens remotely, so the two would say overlapping things, and only one
                // banner may exist (see below).
                val degraded = connection == ConnState.Polling
                val banner = degraded || remote
                Column {
                    // Whichever element is on top owns the status-bar inset, and only
                    // one of them may. This is mobile.css's `.banner ~ .bar {
                    // padding-top: 0 }` — without it the banner paints under the clock.
                    if (degraded) {
                        DegradedBanner(Modifier.windowInsetsPadding(WindowInsets.statusBars))
                    } else if (remote) {
                        RemoteBanner(
                            health?.host,
                            Modifier.windowInsetsPadding(WindowInsets.statusBars),
                        )
                    }
                    TopAppBar(
                        windowInsets =
                            if (banner) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                        title = {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (pushed) {
                                IconButton(onClick = {
                                    if (onConversation) vm.closeConversation()
                                    if (onSubagent) vm.closeAgent()
                                    if (!nav.popBackStack()) nav.navigate(Sessions)
                                }) { Text("‹", style = MaterialTheme.typography.titleMedium) }
                            }
                        },
                        actions = {
                            // Off the conversation, where the title needs the width and the
                            // composer already says what the session is doing.
                            if (onConversation && conversation?.let { it.turns.isNotEmpty() || it.events.isNotEmpty() } == true) {
                                IconButton(onClick = { turnsOpen = true }) {
                                    Icon(Icons.Default.List, contentDescription = "Turns")
                                }
                            }
                            if (onConversation && conversation != null) {
                                Box {
                                    IconButton(onClick = { overflowOpen = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Changes and scratchpad") },
                                            onClick = { overflowOpen = false; filesOpen = true },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Scheduled messages") },
                                            onClick = { overflowOpen = false; laterOpen = true },
                                        )
                                        // Only with a process to replace: with none, the next
                                        // send starts one with today's tools anyway.
                                        conversation?.takeIf { it.runner?.hasProcess == true }?.let { live ->
                                            DropdownMenuItem(
                                                text = { Text("Restart process") },
                                                onClick = { overflowOpen = false; vm.restartProcess(live.sessionId) },
                                            )
                                        }
                                        val open = conversation
                                        val orchOn = open != null &&
                                            (orchPayloads[open.sessionId]?.enabled ?: (open.summary.orchestrator != null))
                                        if (open != null && orchOn) {
                                            DropdownMenuItem(
                                                text = { Text("Orchestrator settings") },
                                                onClick = { overflowOpen = false; orchSettingsFor = open.sessionId },
                                            )
                                        } else if (open != null && open.summary.worker == null) {
                                            // A worker cannot be one: orchestrators only go one level deep.
                                            DropdownMenuItem(
                                                text = { Text("Make it an orchestrator") },
                                                onClick = { overflowOpen = false; vm.makeOrchestrator(open.sessionId) },
                                            )
                                        }
                                    }
                                }
                            }
                            if (onTasks) {
                                IconButton(onClick = { nav.navigate(Schedules) }) {
                                    Icon(Icons.Default.DateRange, contentDescription = "Schedules")
                                }
                            }
                            if (!onConversation && !onSubagent) {
                                QuotaPill(quota, onClick = { quotaOpen = true })
                                if (!onNotifications) {
                                    IconButton(onClick = { nav.navigate(Notifications) }) {
                                        BadgedBox(badge = {
                                            if (inbox.unread > 0) Badge { Text("${inbox.unread}") }
                                        }) { Icon(Icons.Default.Notifications, contentDescription = "History") }
                                    }
                                }
                            }
                            ConnectionDot(connection, Modifier.padding(end = 8.dp))
                            IconButton(onClick = { nav.navigate(Settings) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            },
            bottomBar = {
                if (showTabs) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.heightIn(min = Metrics.TabsHeight),
                    ) {
                        NavigationBarItem(
                            selected = destination?.hasRoute(Sessions::class) == true,
                            onClick = { nav.navigateTab(Sessions) },
                            icon = {
                                // The waiting badge lives here now that the needs-you cards
                                // do: a badge on one tab pointing at a section of another
                                // would send you to the wrong place.
                                BadgedBox(badge = {
                                    val waiting = board?.waiting ?: 0
                                    if (waiting > 0) Badge { Text("$waiting") }
                                }) { Icon(Icons.Default.List, contentDescription = null) }
                            },
                            label = { Text("Sessions") },
                        )
                        NavigationBarItem(
                            selected = destination?.hasRoute(Tasks::class) == true,
                            onClick = { nav.navigateTab(Tasks) },
                            icon = {
                                // Counts what is *open*, never what the filter is showing.
                                BadgedBox(badge = {
                                    if (openTasks > 0) Badge { Text("$openTasks") }
                                }) { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                            },
                            label = { Text("Tasks") },
                        )
                        NavigationBarItem(
                            selected = destination?.hasRoute(NewSession::class) == true,
                            onClick = { nav.navigateTab(NewSession) },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            label = { Text("New") },
                        )
                    }
                }
            },
        ) { insets ->
            Box(Modifier.padding(insets)) {
                // A gate, not a destination: with no token every tab leads somewhere
                // that cannot load, so there are no tabs.
                if (connection == ConnState.NotPaired && !onSettings) {
                    NotPairedScreen(onOpenSettings = { nav.navigate(Settings) })
                    return@Box
                }
                if (connection == ConnState.AccessDenied && !onSettings) {
                    AccessDeniedScreen(onOpenSettings = { nav.navigate(Settings) })
                    return@Box
                }

                NavHost(navController = nav, startDestination = Sessions) {
                    composable<Sessions> {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = vm::refresh,
                        ) {
                            SessionsScreen(
                                sessions = sessions,
                                board = board,
                                answering = answering,
                                askingSessions = askingSessions,
                                filter = filter,
                                onFilterChanged = { filter = it },
                                onOpen = { nav.navigate(Conversation(it)) },
                                onAnswer = { ask, decision -> vm.answerFromBoard(ask, decision) },
                                prs = prs,
                                onOptions = vm::openSessionSheet,
                                showArchived = showArchived,
                                onShowArchivedChanged = { showArchived = it },
                            )
                        }
                    }
                    composable<Tasks> {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = vm::refresh,
                        ) {
                            TasksScreen(
                                tasks = tasks,
                                filter = taskFilter,
                                acting = acting,
                                onFilterChanged = vm::setTaskFilter,
                                actions = taskActions,
                            )
                        }
                    }
                    composable<NewSession> {
                        val dirs = remember(sessions) { recentDirs(sessions) }
                        LaunchedEffect(dirs) {
                            if (newSession.cwd == null) dirs.firstOrNull()?.let { vm.setNewCwd(it.path) }
                        }
                        NewSessionScreen(
                            dirs = dirs,
                            cwd = newSession.cwd,
                            onCwdChanged = vm::setNewCwd,
                            mode = newSession.mode,
                            modes = health?.offeredModes().orEmpty(),
                            onModeChanged = vm::setNewMode,
                            model = newSession.model,
                            onModelChanged = vm::setNewModel,
                            effort = newSession.effort,
                            onEffortChanged = vm::setNewEffort,
                            prompt = newSession.prompt,
                            onPromptChanged = vm::setNewPrompt,
                            test = newSession.test,
                            onTestChanged = vm::setNewTest,
                            attachments = newSession.attachments,
                            onAttach = vm::attachNew,
                            onRemoveAttachment = vm::unstageNew,
                            starting = newSession.starting,
                            savingDraft = newSession.savingDraft,
                            error = newSession.error,
                            drafts = drafts,
                            editingDraftId = newSession.editingDraftId,
                            onStart = { vm.startSession { id -> nav.navigate(Conversation(id)) } },
                            onSaveDraft = vm::saveDraft,
                            onEditDraft = vm::editDraft,
                            onStartDraft = { draft ->
                                vm.startDraft(draft) { id -> nav.navigate(Conversation(id)) }
                            },
                            onDeleteDraft = vm::deleteDraft,
                            onClearDraftEdit = vm::clearDraftEdit,
                            orchestrator = newSession.orchestrator,
                            onOrchestratorChanged = vm::setNewOrchestrator,
                            // Offered only when there is somewhere to send it — the list,
                            // not the session rows, is the answer to that.
                            onSendToOrchestrator = { picking = PickTarget.Form(newSession.prompt) }
                                .takeIf { !orchList.isNullOrEmpty() },
                            onSendDraftToOrchestrator = { d: Draft -> picking = PickTarget.FromDraft(d) }
                                .takeIf { !orchList.isNullOrEmpty() },
                        )
                    }
                    composable<Conversation> { backStackEntry ->
                        val route = backStackEntry.toRoute<Conversation>()
                        LaunchedEffect(route.sessionId) { vm.open(route.sessionId) }
                        val mode = vm.modeFor(route.sessionId)
                        val sessionId = route.sessionId
                        // Every conversation asks whether it is an orchestrator: the row's
                        // field can lag a role turned on elsewhere, and the answer is small.
                        androidx.compose.runtime.DisposableEffect(sessionId) {
                            vm.orchestrators.watch(sessionId)
                            onDispose { vm.orchestrators.unwatch(sessionId) }
                        }
                        val shown = conversation?.takeIf { it.sessionId == sessionId }
                        val summary = shown?.summary ?: sessions.firstOrNull { it.sessionId == sessionId }
                        val orchPayload = orchPayloads[sessionId]
                        val isOrch = orchPayload?.enabled ?: (summary?.orchestrator != null)
                        // A worker's banner says where it came from, which only its
                        // orchestrator's payload knows.
                        val hostId = summary?.worker?.orchestratorId
                        LaunchedEffect(hostId) { hostId?.let { vm.orchestrators.load(it) } }
                        // The overview is what an orchestrator opens on, as on the desktop.
                        var overview by rememberSaveable(sessionId) { mutableStateOf(true) }
                        Column(Modifier.fillMaxSize()) {
                            if (isOrch) {
                                val needs = orchPayload?.let {
                                    needsCount(
                                        needsYou(it, shown?.runner?.state, shown?.ask, now),
                                    )
                                } ?: 0
                                OrchestratorTabs(
                                    overview = overview,
                                    needs = needs,
                                    onOverview = { overview = it },
                                )
                            }
                            summary?.worker?.takeIf { !isOrch }?.let { worker ->
                                val host = sessions.firstOrNull { it.sessionId == worker.orchestratorId }
                                // A worker whose orchestrator is gone is drawn as an ordinary session.
                                if (host != null || orchPayloads[worker.orchestratorId]?.enabled == true) {
                                    WorkerBanner(
                                        orchestratorTitle = host?.displayTitle,
                                        origin = orchPayloads[worker.orchestratorId]?.workers
                                            ?.firstOrNull { it.id == sessionId }?.origin,
                                        closed = worker.closed,
                                        onOpenOrchestrator = { nav.navigate(Conversation(worker.orchestratorId)) },
                                    )
                                }
                            }
                            if (isOrch && overview) {
                                OrchestratorScreen(
                                    payload = orchPayload,
                                    orchState = shown?.runner?.state,
                                    orchAsk = shown?.ask,
                                    sessions = sessions,
                                    prs = prs,
                                    quota = quota?.windows.orEmpty(),
                                    busy = orchBusy,
                                    nowMillis = now,
                                    actions = remember(sessionId) {
                                        OrchestratorActions(
                                            onOpenSession = { nav.navigate(Conversation(it)) },
                                            onShowConversation = { overview = false },
                                            onOpenSettings = { orchSettingsFor = sessionId },
                                            onResume = { vm.resumeOrchestrator(sessionId) },
                                            onAnswerDecision = { d, text -> vm.answerDecision(sessionId, d, text) },
                                            onDismiss = { vm.dismissInboxItem(sessionId, it) },
                                            onWithdraw = { vm.withdrawTask(sessionId, it) },
                                            onAnswerWorker = { workerId, body -> vm.answer(workerId, body) },
                                            onOpenUrl = openUrl,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            } else ConversationScreen(
                                modifier = Modifier.weight(1f),
                                conversation = conversation?.takeIf { it.sessionId == route.sessionId },
                                expandedTools = vm.expandedTools,
                                onToggleTool = vm::toggleTool,
                                draft = draft,
                                onDraftChanged = vm::setDraft,
                                mode = mode,
                                modes = health?.offeredModes().orEmpty(),
                                onModeChanged = { vm.setMode(route.sessionId, it) },
                                sending = sending,
                                answering = answering.isNotEmpty(),
                                nowMillis = now,
                                onSend = {
                                    vm.send(sessionId, mode) { id ->
                                        nav.navigate(Conversation(id)) {
                                            popUpTo(Conversation(sessionId)) { inclusive = true }
                                        }
                                    }
                                },
                                onStop = { vm.stop(route.sessionId) },
                                onAnswer = { body -> vm.answer(route.sessionId, body) },
                                onLoadAll = vm::loadAll,
                                onOpenUrl = openUrl,
                                starting = opening == route.sessionId,
                                suggestionActions = taskActions,
                                acting = acting,
                                controls = ComposerControls(
                                    model = vm.modelFor(sessionId),
                                    onModelChanged = { vm.setModel(sessionId, it) },
                                    effort = vm.effortFor(sessionId),
                                    onEffortChanged = { vm.setEffort(sessionId, it) },
                                    branchBanner = branch?.takeIf { it.sessionId == sessionId }?.banner,
                                    onCancelBranch = vm::cancelBranch,
                                    snippets = remember(snippets, conversation?.summary?.cwd) {
                                        val cwd = conversation?.summary?.cwd
                                        snippets.snippets.filter { it.appliesIn(cwd) }
                                    },
                                    snippetGroups = snippets.groups,
                                    onSendCanned = { text, snippetMode -> vm.sendCanned(sessionId, text, snippetMode) },
                                    slashCommands = slash,
                                    onWantSlashCommands = { vm.loadSlashCommands(sessionId) },
                                    onScheduleSend = { at, chosen -> vm.scheduleSend(sessionId, at, chosen) },
                                    attachments = staged,
                                    onAttach = { uris -> vm.attach(sessionId, uris) },
                                    onRemoveAttachment = vm::unstage,
                                    onRetryAttachment = { vm.retryStaged(sessionId, it) },
                                ),
                                userActions = remember(sessionId) {
                                    UserTurnActions(
                                        onEditResend = vm::editAndResend,
                                        onEditBranch = { vm.editAndBranch(sessionId, it) },
                                    )
                                },
                                onDropQueued = { vm.dropQueued(sessionId, it) },
                                onMoveQueued = { id, by -> vm.moveQueued(sessionId, id, by) },
                                onLoadEarlier = vm::loadEarlier,
                                onOpenAgent = { toolUseId, title ->
                                    vm.openAgent(sessionId, toolUseId, title)
                                    nav.navigate(Subagent(sessionId, toolUseId))
                                },
                                turnsOpen = turnsOpen,
                                onTurnsOpenChanged = { turnsOpen = it },
                                later = later.filter { it.sessionId == sessionId },
                                onOpenLater = { laterOpen = true },
                            )
                        }
                    }
                    composable<Subagent> { backStackEntry ->
                        val route = backStackEntry.toRoute<Subagent>()
                        // Re-entered on the way back from a nested subagent, whose open
                        // replaced this one's view — so it asks for its own again.
                        LaunchedEffect(route.toolUseId) {
                            if (vm.repo.agentView.value?.toolUseId != route.toolUseId) {
                                vm.openAgent(route.sessionId, route.toolUseId, null)
                            }
                        }
                        // The system back gesture skips the top bar's arrow, so the follow is
                        // dropped here too — otherwise the bridge keeps tailing an agent
                        // nobody is looking at.
                        androidx.activity.compose.BackHandler {
                            vm.closeAgent()
                            nav.popBackStack()
                        }
                        SubagentScreen(
                            view = agentView?.takeIf { it.toolUseId == route.toolUseId },
                            expandedTools = vm.expandedTools,
                            onToggleTool = vm::toggleTool,
                            onOpenAgent = { toolUseId, title ->
                                vm.openAgent(route.sessionId, toolUseId, title)
                                nav.navigate(Subagent(route.sessionId, toolUseId))
                            },
                        )
                    }
                    composable<Notifications> {
                        NotificationsScreen(
                            inbox = inbox,
                            onOpenSession = { nav.navigate(Conversation(it)) },
                            onMarkSessionRead = { vm.status.markRead(it) },
                            onMarkAllRead = { vm.status.markRead(null) },
                            onClear = vm.status::clear,
                        )
                    }
                    composable<Schedules> {
                        val schedules by vm.workspace.schedules.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { vm.workspace.loadSchedules() }
                        SchedulesScreen(
                            schedules = schedules,
                            dirs = remember(sessions) { recentDirs(sessions) },
                            modes = health?.offeredModes().orEmpty(),
                            api = vm.api,
                            onSetEnabled = vm::setScheduleEnabled,
                            onRun = { vm.runSchedule(it) { id -> nav.navigate(Conversation(id)) } },
                            onDelete = { vm.workspace.deleteSchedule(it.id) },
                            onSave = vm::saveSchedule,
                            onOpenSession = { nav.navigate(Conversation(it)) },
                        )
                    }
                    composable<Snippets> {
                        SnippetsScreen(
                            payload = snippets,
                            modes = health?.offeredModes().orEmpty(),
                            onSave = vm::saveSnippet,
                            onDelete = { vm.workspace.deleteSnippet(it.id) },
                            onMove = vm.workspace::moveSnippet,
                            onSaveGroup = vm::saveSnippetGroup,
                            onDeleteGroup = { vm.workspace.deleteGroup(it.id) },
                        )
                    }
                    composable<Settings> {
                        val background by vm.background.status.collectAsStateWithLifecycle()
                        SettingsScreen(
                            state = settingsUi,
                            onPasteChanged = vm::onPaste,
                            onOriginChanged = vm::onOrigin,
                            onTokenChanged = vm::onToken,
                            onAccessIdChanged = vm::onAccessId,
                            onAccessSecretChanged = vm::onAccessSecret,
                            onTest = vm::test,
                            onSave = vm::save,
                            onForget = vm::forget,
                            onStayConnectedChanged = onStayConnected,
                            onInstallAppsChanged = vm::setInstallApps,
                            onGrantInstallPermission = vm::grantInstallPermission,
                            onOpenSnippets = { nav.navigate(Snippets) }.takeIf { !connection.isRefused && connection != ConnState.Unconfigured },
                            onOpenInstalls = { nav.navigate(Installs) }.takeIf { !connection.isRefused && connection != ConnState.Unconfigured },
                            background = background,
                            now = now,
                            onBackgroundFix = onBackgroundFix,
                            onAppDetails = { BackgroundIntents.openAppDetails(context) },
                        )
                    }
                    composable<Installs> {
                        val installs by vm.phoneInstalls.records.collectAsStateWithLifecycle()
                        val retrying by vm.retryingInstalls.collectAsStateWithLifecycle()
                        val background by vm.background.status.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { vm.refreshInstalls() }
                        InstallsScreen(
                            records = installs,
                            now = now,
                            retrying = retrying,
                            onRetry = vm::retryInstall,
                            batteryExempt = background.batteryExempt,
                            onAllowBattery = { BackgroundIntents.openBatteryExemption(context) },
                            onAppDetails = { BackgroundIntents.openAppDetails(context) },
                        )
                    }
                }
            }
        }
    }

    // Hosted beside the Scaffold rather than inside a screen: it is opened from two
    // places — a long press on a row, and later possibly the conversation chips — and
    // it is its own window, so nothing in the layout above clips it.
    prSheet?.let { sheet ->
        PrSheet(state = sheet, onDismiss = vm::closePrSheet, onOpenUrl = openUrl)
    }

    val openId = conversation?.sessionId
    if (filesOpen && openId != null && onConversation) {
        ChangesSheet(api = vm.api, sessionId = openId, onDismiss = { filesOpen = false })
    }
    if (laterOpen && openId != null && onConversation) {
        LaterSheet(
            messages = later.filter { it.sessionId == openId },
            onSendNow = { vm.workspace.sendMessageNow(it.id) },
            onReschedule = { moving = it },
            onCancel = { vm.workspace.cancelMessage(it.id) },
            onDismiss = { laterOpen = false },
        )
    }
    moving?.let { message ->
        ScheduleSendDialog(mode = message.permissionMode, modes = listOf(message.permissionMode)) { picked ->
            moving = null
            if (picked != null) vm.rescheduleMessage(message.id, picked.first)
        }
    }

    // Once, the first time an install arrives while Android may still freeze the app.
    val batteryPrompt by vm.background.batteryPrompt.collectAsStateWithLifecycle()
    if (batteryPrompt) {
        AlertDialog(
            onDismissRequest = vm.background::dismissBatteryPrompt,
            title = { Text("Let installs run in the background?") },
            text = {
                Text(
                    "Android is optimising TGXCode's battery use, so it can freeze the app and drop " +
                        "its connection, and an app sent from the desktop then waits until you open " +
                        "TGXCode. Allow it to run in the background to install straight away. On a " +
                        "Motorola, also set App info → App battery usage → Allow background usage.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.background.dismissBatteryPrompt()
                    BackgroundIntents.openBatteryExemption(context)
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = vm.background::dismissBatteryPrompt) { Text("Not now") } },
        )
    }

    restartConfirm?.let { confirm ->
        val tasks = if (confirm.count == 1) "1 background task" else "${confirm.count} background tasks"
        AlertDialog(
            onDismissRequest = vm::dismissRestartConfirm,
            title = { Text("Restart the process?") },
            text = { Text("$tasks will stop.") },
            confirmButton = {
                TextButton(onClick = { vm.restartProcess(confirm.sessionId, force = true) }) {
                    Text("Restart", color = com.twentygx.tgxcode.ui.theme.tgx.red)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissRestartConfirm) { Text("Keep them running") } },
        )
    }

    orchSettingsFor?.let { id ->
        val payload = orchPayloads[id]
        // Turned off, here or elsewhere: there is nothing left to set.
        LaunchedEffect(payload?.enabled) { if (payload?.enabled == false) orchSettingsFor = null }
        if (payload != null && payload.enabled) {
            OrchestratorSettingsSheet(
                settings = payload.settings,
                paused = payload.paused != null,
                nowMillis = now,
                onSave = { vm.saveOrchestratorSettings(id, it) },
                onResume = { vm.resumeOrchestrator(id) },
                onTurnOff = { orchSettingsFor = null; vm.stopOrchestrating(id) },
                onDismiss = { orchSettingsFor = null },
            )
        }
    }

    picking?.let { target ->
        // Asked fresh on every open, as the desktop's picker does: which orchestrators
        // are live, paused or busy is exactly what moves while you are not looking.
        LaunchedEffect(target) { vm.orchestrators.loadList() }
        OrchestratorPickerSheet(
            subject = when (target) {
                is PickTarget.FromDraft -> target.draft.displayTitle
                is PickTarget.Form -> target.prompt.lineSequence().firstOrNull { it.isNotBlank() }
            },
            rows = orchList,
            onPick = { row ->
                picking = null
                when (target) {
                    is PickTarget.FromDraft -> vm.sendDraftToOrchestrator(row.orchestratorId, target.draft)
                    is PickTarget.Form -> vm.sendFormToOrchestrator(row.orchestratorId)
                }
            },
            onDismiss = { picking = null },
        )
    }

    if (quotaOpen) {
        QuotaSheet(
            quota = quota,
            refreshing = refreshingQuota,
            onRefresh = vm.status::refreshQuota,
            onDismiss = { quotaOpen = false },
        )
    }

    sessionSheet?.let { session ->
        SessionActionsSheet(
            session = session,
            actions = remember {
                SessionSheetActions(
                    onPin = vm::setPinned,
                    onArchive = vm::setArchived,
                    onRename = vm::rename,
                    onDelete = vm::deleteSession,
                    onPrs = vm::openPrSheet,
                )
            },
            onDismiss = vm::closeSessionSheet,
        )
    }
}

private fun NavCommand.Follow.from(): String = sessionId

/** What the Send-to-orchestrator picker is handing over. */
private sealed interface PickTarget {
    data class FromDraft(val draft: Draft) : PickTarget
    data class Form(val prompt: String) : PickTarget
}

/** Tab switching keeps each tab's scroll position and filter, which the web loses. */
private fun androidx.navigation.NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
