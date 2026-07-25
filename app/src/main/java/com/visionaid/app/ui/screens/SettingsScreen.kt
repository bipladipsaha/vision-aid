package com.visionaid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.settings.SettingsRepository
import com.visionaid.app.ui.theme.neoPressed
import com.visionaid.app.ui.theme.neoRaised
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    val voiceSpeed by settingsRepository.voiceSpeedFlow.collectAsState(initial = 1.0f)
    val voicePitch by settingsRepository.voicePitchFlow.collectAsState(initial = 1.0f)
    val hapticIntensity by settingsRepository.hapticIntensityFlow.collectAsState(initial = 1.0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TopAppBar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Accessibility",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Reader
            SettingsCard(
                icon = Icons.Default.PlayArrow,
                title = "Screen Reader",
                subtitle = "Read out elements on the screen.",
                content = {
                    Switch(
                        checked = true, 
                        onCheckedChange = { /* Placeholder */ },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            )

            // High Contrast
            SettingsCard(
                icon = Icons.Default.CheckCircle,
                title = "High Contrast",
                subtitle = "Increase contrast for better visibility.",
                content = {
                    Switch(
                        checked = false, 
                        onCheckedChange = { /* Placeholder */ },
                        colors = SwitchDefaults.colors(
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            )

            // Haptic Feedback
            SettingsCard(
                icon = Icons.Default.Build,
                title = "Haptic Feedback",
                subtitle = "Provide physical feedback for actions.",
                content = {
                    Switch(
                        checked = hapticIntensity > 0f, 
                        onCheckedChange = { 
                            scope.launch { settingsRepository.setHapticIntensity(if (it) 1.0f else 0f) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            )

            // Voice Speed (Demo Mode binding for now since it's an example)
            SettingsCard(
                icon = Icons.Default.Info,
                title = "Voice Speed",
                subtitle = "Adjust the reading speed.",
                content = {
                    Slider(
                        value = voiceSpeed,
                        onValueChange = { 
                            scope.launch { settingsRepository.setVoiceSpeed(it) }
                        },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            )

        }
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neoRaised(cornerRadius = 24.dp, blurRadius = 16.dp, offset = 8.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .neoPressed(cornerRadius = 24.dp, blurRadius = 8.dp, offset = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
