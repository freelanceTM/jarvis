package com.jarvis.assistant.presentation.translator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.agent.translator.InterpreterPreset
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveInterpreterScreen(
    onNavigateBack: () -> Unit,
    viewModel: LiveInterpreterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSourceDropdown by remember { mutableStateOf(false) }
    var showTargetDropdown by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = JarvisBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.sinhronnyy_perevodchik),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.perevod_rechi_sobesednika_pryamo_v_naushnik),
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisCyanPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nazad),
                            tint = JarvisCyanPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.history.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.ochistit_istoriyu),
                                tint = TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Language Pair Selector Pill (Source ⇄ Target)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = JarvisCardBackground,
                border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Source Language Selector
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = JarvisSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSourceDropdown = true }
                        ) {
                            Text(
                                text = uiState.sourceLanguage.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = JarvisCyanPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showSourceDropdown,
                            onDismissRequest = { showSourceDropdown = false }
                        ) {
                            LiveTranslatorEngine.SUPPORTED_LANGUAGES.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.displayName) },
                                    onClick = {
                                        viewModel.setSourceLanguage(lang)
                                        showSourceDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Swap Button
                    IconButton(
                        onClick = { viewModel.swapLanguages() },
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(JarvisSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.pomenyat_yazyki),
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Target Language Selector
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = JarvisSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTargetDropdown = true }
                        ) {
                            Text(
                                text = uiState.targetLanguage.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = JarvisGreen,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showTargetDropdown,
                            onDismissRequest = { showTargetDropdown = false }
                        ) {
                            LiveTranslatorEngine.SUPPORTED_LANGUAGES.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.displayName) },
                                    onClick = {
                                        viewModel.setTargetLanguage(lang)
                                        showTargetDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 1.1 Быстрые режимы переводчика: AUTO / RU→TM / TM→RU / EN→RU / RU→EN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InterpreterPreset.all.forEach { preset ->
                    val isActive = uiState.preset == preset
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isActive) JarvisCyanPrimary.copy(alpha = 0.18f) else JarvisSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) JarvisCyanPrimary else JarvisSurfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.applyPreset(preset) }
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isActive) JarvisCyanPrimary else TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Active Status & Partial Recognition Banner
            if (uiState.isListening || uiState.partialRecognizedText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = JarvisCyanPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = JarvisCyanPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                uiState.partialRecognizedText.isNotBlank() ->
                                    stringResource(R.string.slyshu, uiState.partialRecognizedText)
                                uiState.preset == InterpreterPreset.AUTO ->
                                    stringResource(R.string.slushayu_sobesednika_yazyk_opredelyaetsya_avtomaticheski)
                                else ->
                                    stringResource(R.string.slushayu_rech_sobesednika, uiState.sourceLanguage.displayName)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisCyanPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // 3. Conversation Stream
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.history.isEmpty() && !uiState.isListening) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = JarvisCyanPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.rezhim_ear_interpreter),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.nadente_naushnik_i_nazhmite_knopku_nizhe_jarvis_budet_slusha),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.history, key = { it.id }) { item ->
                            TranslationStreamCard(item = item)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Primary Live Ear Listening Action Button
            Button(
                onClick = { viewModel.toggleListening() },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isListening) JarvisRed else JarvisGreen,
                    contentColor = JarvisBackground
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .shadow(12.dp, RoundedCornerShape(22.dp), ambientColor = JarvisCyanGlow)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (uiState.isListening) Icons.Default.Stop else Icons.Default.Hearing,
                        contentDescription = null,
                        tint = JarvisBackground,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (uiState.isListening) stringResource(R.string.ostanovit_perevod) else stringResource(R.string.slushat_perevod_v_naushnik),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = JarvisBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun TranslationStreamCard(item: TranslationItem) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
        border = BorderStroke(1.dp, JarvisSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Original Phrase
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sobesednik, item.sourceLang),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
            Text(
                text = item.originalText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JarvisSurface)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Translated to Earbud Phrase
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = null,
                        tint = JarvisCyanPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.perevod_v_uho, item.targetLang),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = JarvisCyanPrimary
                    )
                }
            }
            Text(
                text = item.translatedText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
        }
    }
}
