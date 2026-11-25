package com.merrimentlabs.ducky.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
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
 * Every row that leaves the app hands a link to the browser or the mail app, so Ducky itself
 * never goes online. Releases is how a sideloaded app learns about updates without a network
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
    fun browse(urlRes: Int): () -> Unit = { haptic(); openUrl(context, context.getString(urlRes)) }
    Column(
        modifier = Modifier
            .padding(bottom = 24.dp)
            .bringIntoViewRequester(aboutRequester)
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
        Text(
            text = stringResource(R.string.copyright_line),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp)
        )
    }
}

/** Opens a web page in the browser; Ducky itself never goes online. */
private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/** An email to Merriment Labs, the version in the subject. */
private fun emailUs(context: Context) {
    val subject = context.getString(R.string.app_version_line, context.getString(R.string.app_name), BuildConfig.VERSION_NAME)
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.contact_email)))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    // No mail app: put the address on the clipboard instead (Android confirms the copy itself).
    runCatching { context.startActivity(intent) }.onFailure {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.contact), context.getString(R.string.contact_email))
        )
    }
}

/** The About header: the app's icon (in the user's accent), "Ducky <version>" and its one line. */
@Composable
private fun AboutHeader() {
    val context = LocalContext.current
    // Re-read on every resume: the icon follows the accent once the user has left the app
    // (MainActivity.syncLauncherIcon), and this screen can outlive that trip.
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    LifecycleResumeEffect(Unit) {
        icon = LauncherIcon.currentIcon(context)?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() }
        onPauseOrDispose { }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_version_line, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.app_tagline),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
