package com.jarvis.assistant.presentation.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

data class PermissionItem(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isRequired: Boolean = false
)

@Composable
fun PermissionsScreen(
    onAllPermissionsGranted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    
    val permissionItems = remember {
        listOf(
            PermissionItem(
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Микрофон",
                description = "Для голосовых команд и распознавания речи",
                icon = Icons.Default.Mic,
                isRequired = true
            ),
            PermissionItem(
                permission = Manifest.permission.CALL_PHONE,
                title = "Телефон",
                description = "Для совершения звонков голосом",
                icon = Icons.Default.Phone,
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.READ_CONTACTS,
                title = "Контакты",
                description = "Для поиска контактов по имени",
                icon = Icons.Default.Contacts,
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.SEND_SMS,
                title = "SMS",
                description = "Для отправки сообщений голосом",
                icon = Icons.Default.Sms,
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Геолокация",
                description = "Для навигации и определения местоположения",
                icon = Icons.Default.LocationOn,
                isRequired = false
            ),
            PermissionItem(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 
                    Manifest.permission.BLUETOOTH_CONNECT 
                else Manifest.permission.BLUETOOTH,
                title = "Bluetooth",
                description = "Для работы с беспроводными наушниками",
                icon = Icons.Default.Bluetooth,
                isRequired = false
            ),
            PermissionItem(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = "Уведомления",
                description = "Для отображения статуса JARVIS",
                icon = Icons.Default.Notifications,
                isRequired = false
            )
        )
    }
    
    var permissionStates by remember {
        mutableStateOf(
            permissionItems.associate { 
                it.permission to isPermissionGranted(context, it.permission) 
            }
        )
    }
    
    val multiplePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionStates = permissionStates.toMutableMap().apply {
            permissions.forEach { (permission, granted) ->
                this[permission] = granted
            }
        }
        
        // Проверяем, все ли обязательные разрешения получены
        val allRequiredGranted = permissionItems
            .filter { it.isRequired }
            .all { permissionStates[it.permission] == true }
        
        if (allRequiredGranted) {
            onAllPermissionsGranted()
        }
    }
    
    val allRequiredGranted = permissionItems
        .filter { it.isRequired }
        .all { permissionStates[it.permission] == true }
    
    val grantedCount = permissionStates.values.count { it }
    val totalCount = permissionItems.size
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // Header
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Разрешения JARVIS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Для полноценной работы голосового ассистента необходимы следующие разрешения",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Progress indicator
        LinearProgressIndicator(
            progress = { grantedCount.toFloat() / totalCount.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        
        Text(
            text = "$grantedCount из $totalCount разрешений",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Permission list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(permissionItems) { item ->
                PermissionCard(
                    item = item,
                    isGranted = permissionStates[item.permission] == true,
                    onRequestPermission = {
                        multiplePermissionsLauncher.launch(arrayOf(item.permission))
                    },
                    onOpenSettings = {
                        openAppSettings(context)
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Buttons
        Button(
            onClick = {
                val notGranted = permissionItems
                    .filter { permissionStates[it.permission] != true }
                    .map { it.permission }
                    .toTypedArray()
                
                if (notGranted.isNotEmpty()) {
                    multiplePermissionsLauncher.launch(notGranted)
                } else {
                    onAllPermissionsGranted()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (allRequiredGranted) "Продолжить" else "Запросить все разрешения",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        if (!allRequiredGranted) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "Пропустить (ограниченный режим)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    item: PermissionItem,
    isGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.Check else item.icon,
                    contentDescription = null,
                    tint = if (isGranted) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.isRequired) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Обязательно",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (!isGranted) {
                IconButton(onClick = onRequestPermission) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Запросить",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == 
        PackageManager.PERMISSION_GRANTED
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
