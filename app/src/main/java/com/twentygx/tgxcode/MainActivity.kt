package com.twentygx.tgxcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twentygx.tgxcode.ui.AppViewModel
import com.twentygx.tgxcode.ui.TgxCodeRoot
import com.twentygx.tgxcode.ui.theme.TgxCodeTheme

class MainActivity : ComponentActivity() {

    // Activity-level Compose state, not plain fields: an intent arriving while the
    // app is already running has to actually trigger a recomposition, and a field
    // read during composition never will.
    private val pendingSessionId = mutableStateOf<String?>(null)
    private val pendingPairing = mutableStateOf<String?>(null)
    private val pendingInstalls = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Not optional at targetSdk 36 — since 35 there is no opt-out, and without
        // it the composer renders under the gesture bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consume(intent)

        setContent {
            TgxCodeTheme {
                val container = (application as TgxApp).container
                val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(container))

                val sessionId by pendingSessionId
                val pairing by pendingPairing
                val installs by pendingInstalls

                LaunchedEffect(Unit) {
                    vm.onStart()
                    vm.probe()
                }

                // Requested in context, from the toggle — never on first launch.
                // The service runs without it, but its notification is invisible and
                // every ask notification is silently dropped, which is the feature.
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) vm.applyStayConnected(true)
                }

                TgxCodeRoot(
                    vm = vm,
                    deepLinkSessionId = sessionId,
                    pairingLink = pairing,
                    openInstalls = installs,
                    onDeepLinkHandled = { pendingSessionId.value = null },
                    onInstallsHandled = { pendingInstalls.value = false },
                    onPairingHandled = { pendingPairing.value = null },
                    onStayConnected = { wanted ->
                        if (!wanted) {
                            vm.applyStayConnected(false)
                        } else if (
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.applyStayConnected(true)
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
    }

    // Coming back from the "Install unknown apps" screen is a resume, and is the moment
    // a phone install waiting on that grant can carry on.
    override fun onResume() {
        super.onResume()
        (application as TgxApp).container.phoneInstalls.onResume()
    }

    override fun onPause() {
        (application as TgxApp).container.phoneInstalls.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * Four ways in: the launcher, a `tgxcode://session/<id>` deep link from a
     * notification, `tgxcode://installs` from an install notification, and a pairing
     * link either shared from the browser or opened as an App Link.
     */
    private fun consume(intent: Intent?) {
        when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                pendingPairing.value = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            intent?.action == Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "tgxcode" && data.host == "session") {
                    pendingSessionId.value = data.lastPathSegment
                } else if (data.scheme == "tgxcode" && data.host == "installs") {
                    pendingInstalls.value = true
                } else if (data.path?.startsWith("/pair") == true) {
                    pendingPairing.value = data.toString()
                }
            }
        }
    }
}
