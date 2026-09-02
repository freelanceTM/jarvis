package com.jarvis.assistant.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixHairline
import com.jarvis.assistant.presentation.components.OmnixHistoryIcon
import com.jarvis.assistant.presentation.components.OmnixMeIcon
import com.jarvis.assistant.presentation.core.CoreState
import com.jarvis.assistant.presentation.core.OmnixCore
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * The OMNIX navigation bar: `History | ◎ | Me` (§20, §44, §45).
 *
 * The centre item is the Core itself, rendered small — the same geometry as on
 * Home, in the same eight states. The two side items are thin outline icons
 * with an overline label, dimmed unless selected, so they never compete with
 * the Core for attention (§45).
 */
@Composable
fun OmnixNavigationBar(
    currentRoute: String?,
    coreState: CoreState,
    onNavigate: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    // A floating pill rather than a full-width bar: it keeps the Core visually
    // detached from the screen edge and stops the chrome competing with it
    // (§45, reference poster).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OmnixTheme.radius.pill))
                .background(colors.surface)
                .border(
                    width = OmnixHairline,
                    color = colors.border,
                    shape = RoundedCornerShape(OmnixTheme.radius.pill)
                )
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationItem(
                label = stringResource(R.string.omnix_nav_history),
                selected = currentRoute == OmnixDestination.History.route,
                onClick = { onNavigate(OmnixDestination.History) },
                modifier = Modifier.weight(1f),
                icon = { tint -> OmnixHistoryIcon(color = tint) }
            )

            CoreNavigationItem(
                coreState = coreState,
                selected = currentRoute == OmnixDestination.Home.route,
                onClick = { onNavigate(OmnixDestination.Home) },
                modifier = Modifier.weight(1f)
            )

            NavigationItem(
                label = stringResource(R.string.omnix_nav_me),
                contentDescription = stringResource(R.string.omnix_a11y_open_settings),
                selected = currentRoute == OmnixDestination.Me.route,
                onClick = { onNavigate(OmnixDestination.Me) },
                modifier = Modifier.weight(1f),
                icon = { tint -> OmnixMeIcon(color = tint) }
            )
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: @Composable (Color) -> Unit
) {
    val tint = if (selected) OmnixTheme.colors.textPrimary else OmnixTheme.colors.textTertiary
    Column(
        modifier = modifier
            .height(OmnixTheme.spacing.touchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = LayoutArrangement.Center
    ) {
        icon(tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = OmnixTheme.typography.overline,
            color = tint,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The Core as a navigation item. It is the same component in a smaller size,
 * still state-driven — so the user can see what OMNIX is doing from any
 * screen without going Home (§12, §44).
 */
@Composable
private fun CoreNavigationItem(
    coreState: CoreState,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.omnix_nav_core)
    Box(
        modifier = modifier
            .height(OmnixTheme.spacing.touchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        OmnixCore(
            state = coreState,
            size = OmnixTheme.coreSizes.navigation,
            intensity = if (selected) 1f else 0.7f,
            contentDescription = description,
            modifier = Modifier.size(OmnixTheme.coreSizes.navigation)
        )
    }
}
