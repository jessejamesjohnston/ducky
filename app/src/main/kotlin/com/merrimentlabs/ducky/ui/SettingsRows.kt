package com.merrimentlabs.ducky.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.R

/** The confirm haptic that every settings tap plays. */
@Composable
internal fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) } }
}

/**
 * A settings card: full width, the style's card corner on the surface color, and a thin
 * outline in outlined styles. Style-responsive, so a style tap visibly restyles the settings
 * page itself, not only the timer screen behind it.
 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalAppStyle.current
    val shape = RoundedCornerShape(style.cardCorner)
    Column(
        verticalArrangement = verticalArrangement,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (style.outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                else Modifier
            )
            .padding(contentPadding),
        content = content,
    )
}

/** The divider between rows inside a [SettingsCard]. */
@Composable
internal fun SettingsDivider() = HorizontalDivider(
    color = MaterialTheme.colorScheme.background,
    modifier = Modifier.padding(horizontal = 18.dp)
)

/** Where a row takes the user when it leaves the app (null: a screen inside Ducky). */
internal enum class Leaves { BROWSER, EMAIL }

/**
 * A tappable row that leads somewhere. [icon] is an optional leading symbol. A row that
 * [leaves] the app ends in an external-link arrow and reads "<title>, <subtitle>, opens in
 * browser" (or email) to TalkBack; the others end in › and read as a button.
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    icon: Int? = null,
    leaves: Leaves? = null,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    // A subtitle that is a sentence loses its period, so "online., opens in browser" can't happen.
    val spoken = listOfNotNull(title, subtitle?.trimEnd('.')).joinToString(", ")
    val description = when (leaves) {
        Leaves.BROWSER -> stringResource(R.string.a11y_opens_in_browser, spoken)
        Leaves.EMAIL -> stringResource(R.string.a11y_opens_email, spoken)
        null -> spoken
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = if (leaves == null) Role.Button else null, onClick = onClick)
            .then(
                // One spoken line per row; the symbols are decoration.
                if (icon != null) Modifier.clearAndSetSemantics {
                    contentDescription = description
                    if (leaves == null) role = Role.Button
                    onClick { onClick(); true }
                } else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(end = 16.dp).size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
        }
        if (leaves != null) {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(start = 12.dp).size(20.dp)
            )
        } else {
            Text(
                text = "›",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}

/** A row with a switch; the whole row toggles, and TalkBack reads it as one switch. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // row handles it; avoids a double a11y node
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

/** A section heading above a card, in the accent color and the style's lettering. */
@Composable
internal fun SectionLabel(text: String) {
    val style = LocalAppStyle.current
    Text(
        text = if (style.upperCaseLabels) text.uppercase() else text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = style.clockFont,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, bottom = 8.dp)
    )
}
