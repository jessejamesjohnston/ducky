package com.merrimentlabs.ducky.ui

import androidx.core.graphics.createBitmap
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.drawable.Drawable
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.merrimentlabs.ducky.BuildConfig
import com.merrimentlabs.ducky.LauncherIcon
import com.merrimentlabs.ducky.R

/**
 * The About card, last in Settings. The first block is the one every Merriment Labs app shows,
 * in the same order: the header (icon, name and version), the maker, Source code, Privacy,
 * Open-source notices, License and Releases. Rows only Ducky needs (Help, Contact) come after it,
 * then the copyright line. [revealAbout] scrolls the card into view, for the
 * return from the notices screen.
 *
 * Every row that leaves the app hands a link to the browser ([openLink] decides how) or the
 * mail app, so Ducky itself never goes online. Releases is how a sideloaded app learns about updates without a network
 * check of its own.
 */
@Composable
internal fun AboutCard(
    revealAbout: Boolean,
    onAboutRevealed: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val aboutRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(revealAbout) {
        if (revealAbout) {
            aboutRequester.bringIntoView()
            onAboutRevealed()
        }
    }
    fun browse(urlRes: Int): () -> Unit = { haptic(); openLink(context, context.getString(urlRes)) }
    Column(
        modifier = Modifier.bringIntoViewRequester(aboutRequester)
    ) {
        SectionLabel(stringResource(R.string.section_about))
        // Rows that leave the app end in ↗, the one that opens a screen here in ›.
        SettingsCard {
            AboutHeader()
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.about_maker),
                subtitle = stringResource(R.string.about_maker_subtitle),
                enabled = true,
                icon = R.drawable.ic_person,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.about_maker_url),
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.source_code),
                subtitle = stringResource(R.string.source_code_subtitle),
                enabled = true,
                icon = R.drawable.ic_code,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.source_code_url),
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.privacy_policy),
                subtitle = stringResource(R.string.privacy_policy_subtitle),
                enabled = true,
                icon = R.drawable.ic_shield,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.privacy_policy_url),
            )
            SettingsDivider()
            // Apache-2.0 §4 and BSD: the bundled libraries' license texts, in the app.
            SettingsActionRow(
                title = stringResource(R.string.open_source_licenses),
                subtitle = stringResource(R.string.open_source_licenses_subtitle),
                enabled = true,
                icon = R.drawable.ic_description,
                onClick = { haptic(); onOpenLicenses() },
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.license),
                subtitle = stringResource(R.string.license_subtitle),
                enabled = true,
                icon = R.drawable.ic_license,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.license_url),
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.releases),
                subtitle = stringResource(R.string.releases_subtitle),
                enabled = true,
                icon = R.drawable.ic_releases,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.releases_url),
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.help),
                subtitle = stringResource(R.string.help_subtitle),
                enabled = true,
                icon = R.drawable.ic_help,
                leaves = Leaves.BROWSER,
                onClick = browse(R.string.help_url),
            )
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.contact),
                subtitle = stringResource(R.string.contact_email),
                enabled = true,
                icon = R.drawable.ic_mail,
                leaves = Leaves.EMAIL,
                onClick = { haptic(); emailUs(context) },
            )
        }
        // What Android's backup carries, under the last card (data_extraction_rules.xml).
        Text(
            text = stringResource(R.string.backup_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsMetrics.LABEL_SIDE, end = SettingsMetrics.LABEL_SIDE, top = 12.dp)
        )
        Text(
            text = stringResource(R.string.copyright_line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsMetrics.LABEL_SIDE, end = SettingsMetrics.LABEL_SIDE, top = 12.dp)
        )
    }
}

/** An email to Merriment Labs, the version in the subject. */
private fun emailUs(context: Context) {
    val subject = context.getString(R.string.app_version_line, context.getString(R.string.app_name), BuildConfig.VERSION_NAME)
    openEmail(context, context.getString(R.string.contact_email), subject, label = context.getString(R.string.contact))
}

/**
 * The launcher icon as a square tile [px] wide: an adaptive icon's background and foreground
 * layers, cropped to the 72 of their 108 units a launcher shows, with no mask of the launcher's
 * own shape (the header rounds it to 12dp itself). Any other drawable is drawn as it is.
 */
internal fun iconTile(icon: Drawable, px: Int): Bitmap {
    if (icon !is AdaptiveIconDrawable) return icon.toBitmap(px, px)
    val bitmap = createBitmap(px, px)
    val canvas = Canvas(bitmap)
    val inset = px / 4 // (108 - 72) / 2 of 72
    for (layer in listOfNotNull(icon.background, icon.foreground)) {
        layer.setBounds(-inset, -inset, px + inset, px + inset)
        layer.draw(canvas)
    }
    return bitmap
}

/** The About header: the app's icon (in the user's accent), "Ducky <version>" and its one line. */
@Composable
private fun AboutHeader() {
    val context = LocalContext.current
    // Re-read on every resume: the icon follows the accent once the user has left the app
    // (MainActivity.syncLauncherIcon), and this screen can outlive that trip.
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    LifecycleResumeEffect(Unit) {
        icon = LauncherIcon.currentIcon(context)?.let { runCatching { iconTile(it, 144).asImageBitmap() }.getOrNull() }
        onPauseOrDispose { }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = SettingsMetrics.ROW_SIDE, vertical = 12.dp)
    ) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(SettingsMetrics.ICON_GAP))
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(stringResource(R.string.app_version_line, stringResource(R.string.app_name), BuildConfig.VERSION_NAME))
            RowSubtitle(stringResource(R.string.app_tagline))
        }
    }
}
