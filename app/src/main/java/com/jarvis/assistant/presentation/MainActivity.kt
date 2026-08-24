package com.jarvis.assistant.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.presentation.activation.ActivationScreen
import com.jarvis.assistant.presentation.navigation.JarvisNavGraph
import com.jarvis.assistant.presentation.permissions.PermissionsScreen
import com.jarvis.assistant.presentation.theme.JarvisAssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var licenseManager: LicenseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            JarvisAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showPermissions by remember {
                        mutableStateOf(!hasRequiredPermissions())
                    }
                    val licenseInfo by licenseManager.licenseFlow.collectAsState(
                        initial = licenseManager.getLicenseInfo()
                    )
                    var serverCheckComplete by remember { mutableStateOf(false) }

                    LaunchedEffect(showPermissions) {
                        if (!showPermissions) {
                            // Persisted client state never unlocks the product by itself.
                            licenseManager.refreshFromServer()
                            serverCheckComplete = true
                        }
                    }

                    when {
                        showPermissions -> {
                            PermissionsScreen(
                                onAllPermissionsGranted = {
                                    showPermissions = false
                                },
                                onSkip = {
                                    showPermissions = false
                                }
                            )
                        }
                        !serverCheckComplete -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        !licenseInfo.isActivated || licenseInfo.isExpired -> {
                            ActivationScreen(onActivationSuccess = { serverCheckComplete = true })
                        }
                        else -> {
                            JarvisNavGraph()
                        }
                    }
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
