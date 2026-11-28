package com.merrimentlabs.ducky.ui

import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.RectangleShape
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.R

/** The confirm haptic that every settings tap plays. */
@Composable
internal fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) } }
}

/**
 * The Settings page's measurements, the same as Roastbook's and Driveway's (Driveway's page is
 * the reference). The page is a centered column at most 720dp
 * wide with 16dp sides in a compact window and 24dp from 600dp; each section is an accent label
 * 20dp below the card before it and 8dp above its own card; card rows are at least 56dp tall
 * with 16dp sides and 10dp above and below their text; hairlines are inset 16dp.
 */
internal object SettingsMetrics {
    val COLUMN_MAX = 720.dp
    val SIDE_COMPACT = 16.dp
    val SIDE_WIDE = 24.dp
    val WIDE_FROM = 600.dp
    val PAGE_TOP = 16.dp
    val PAGE_BOTTOM = 24.dp
    val LABEL_TOP = 20.dp
    val LABEL_BOTTOM = 8.dp
    val LABEL_SIDE = 4.dp
    val ROW_MIN_HEIGHT = 56.dp
    val ROW_SIDE = 16.dp
    val ROW_END_BEFORE_ICON = 8.dp
    val ROW_VERTICAL = 10.dp
    val ICON_GAP = 16.dp
    val DIVIDER_INSET = 16.dp

    /** The page's side margin in a window [windowWidth] wide. */
    fun side(windowWidth: Dp): Dp = if (windowWidth < WIDE_FROM) SIDE_COMPACT else SIDE_WIDE
}

/** A card row's title: the row-title style, 16/22sp Medium. */
@Composable
internal fun RowTitle(text: String, alpha: Float = 1f) = Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
)

/** A card row's supporting line: the supporting style, 14/20sp, in the muted color. */
@Composable
internal fun RowSubtitle(text: String, alpha: Float = 1f) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
)

/** The trailing chevron of a row that opens a screen inside Ducky: chevron_right at 24dp. */
@Composable
internal fun RowChevron(alpha: Float = 1f) = Icon(
    painter = painterResource(R.drawable.ic_chevron_right),
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
    modifier = Modifier.padding(start = 8.dp).size(24.dp)
)

/** The padding of a card row that ends in an icon or a glyph (›, ↗). */
internal fun Modifier.rowPadding(endsInIcon: Boolean = true): Modifier =
    heightIn(min = SettingsMetrics.ROW_MIN_HEIGHT).padding(
        start = SettingsMetrics.ROW_SIDE,
        end = if (endsInIcon) SettingsMetrics.ROW_END_BEFORE_ICON else SettingsMetrics.ROW_SIDE,
        top = SettingsMetrics.ROW_VERTICAL,
        bottom = SettingsMetrics.ROW_VERTICAL,
    )

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
    modifier = Modifier.padding(horizontal = SettingsMetrics.DIVIDER_INSET)
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
            .focusRing(RectangleShape, focusRingColor())
            .clickable(enabled = enabled, role = if (leaves == null) Role.Button else null, onClick = onClick)
            .then(
                // One spoken line per row; the symbols are decoration.
                if (icon != null) Modifier.clearAndSetSemantics {
                    contentDescription = description
                    if (leaves == null) role = Role.Button
                    onClick { onClick(); true }
                } else Modifier
            )
            .rowPadding()
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(end = SettingsMetrics.ICON_GAP).size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title, alpha)
            if (subtitle != null) RowSubtitle(subtitle, alpha)
        }
        if (leaves != null) {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
            )
        } else {
            RowChevron(alpha)
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
            .focusRing(RectangleShape, focusRingColor())
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .rowPadding(endsInIcon = false)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title)
            if (subtitle != null) RowSubtitle(subtitle)
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

/**
 * A section heading above a card, in the accent color and the style's lettering: 14/20sp SemiBold,
 * 20dp below what comes before it and 8dp above its card, and a heading for TalkBack.
 */
@Composable
internal fun SectionLabel(text: String) {
    val style = LocalAppStyle.current
    Text(
        text = if (style.upperCaseLabels) text.uppercase() else text,
        style = MaterialTheme.typography.titleSmall,
        fontFamily = style.labelFont,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SettingsMetrics.LABEL_SIDE,
                end = SettingsMetrics.LABEL_SIDE,
                top = SettingsMetrics.LABEL_TOP,
                bottom = SettingsMetrics.LABEL_BOTTOM,
            )
            .semantics { heading() }
    )
}

/** The back arrow at the start of a top bar: a 48dp button. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    val haptic = rememberHaptic()
    IconButton(
        onClick = { haptic(); onBack() },
        modifier = Modifier.focusRing(CircleShape, focusRingColor()),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = stringResource(R.string.back),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The house top bar, inside the page's column: the back button, 4dp, then [title] in the top-bar
 * style (22/28sp SemiBold), a heading for TalkBack; 4dp below.
 */
@Composable
internal fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val style = LocalAppStyle.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(bottom = 4.dp)) {
        BackButton(onBack)
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (style.upperCaseLabels) title.uppercase() else title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = style.labelFont,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}
