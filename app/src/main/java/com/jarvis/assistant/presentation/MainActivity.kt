package com.jarvis.assistant.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.presentation.activation.ActivationScreen
import com.jarvis.assistant.presentation.navigation.OmnixNavGraph
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.data.preferences.OmnixExperienceStore
import com.jarvis.assistant.presentation.core.CoreState
import com.jarvis.assistant.presentation.firstrun.FirstRunRoute
import com.jarvis.assistant.presentation.core.OmnixCore
import com.jarvis.assistant.presentation.design.OmnixTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var licenseManager: LicenseManager

    @Inject
    lateinit var experienceStore: OmnixExperienceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OmnixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OmnixTheme.colors.background
                ) {
                    // First run is driven by a persisted flag, not by the
                    // permission state: a user who skipped the microphone
                    // should not be sent back through onboarding forever.
                    val onboardingCompleted by experienceStore.onboardingCompleted
                        .collectAsState(initial = true)
                    var firstRunDone by remember { mutableStateOf(false) }
                    val showFirstRun = !onboardingCompleted && !firstRunDone
                    val licenseInfo by licenseManager.licenseFlow.collectAsState(
                        initial = licenseManager.getLicenseInfo()
                    )
                    var serverCheckComplete by remember { mutableStateOf(false) }

                    LaunchedEffect(showFirstRun) {
                        if (!showFirstRun) {
                            // Persisted client state never unlocks the product by itself.
                            licenseManager.refreshFromServer()
                            serverCheckComplete = true
                        }
                    }

                    when {
                        showFirstRun -> {
                            FirstRunRoute(
                                onFinished = {
                                    firstRunDone = true
                                    lifecycleScope.launch {
                                        experienceStore.setOnboardingCompleted(true)
                                    }
                                }
                            )
                        }
                        !serverCheckComplete -> {
                            // No spinner anywhere in OMNIX (§29): the Core
                            // itself is the only "working" indicator.
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                OmnixCore(state = CoreState.THINKING)
                            }
                        }
                        !licenseInfo.isActivated || licenseInfo.isExpired -> {
                            ActivationScreen(onActivationSuccess = { serverCheckComplete = true })
                        }
                        else -> {
                            OmnixNavGraph()
                        }
                    }
                }
            }
        }
    }

}
