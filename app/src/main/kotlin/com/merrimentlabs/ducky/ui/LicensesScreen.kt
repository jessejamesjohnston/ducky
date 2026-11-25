package com.merrimentlabs.ducky.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.OpenSourceLicenses
import com.merrimentlabs.ducky.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > About > Open-source notices. Ducky's own license, then one card per
 * bundled library (name, version, license); a tap expands the card to the full
 * license text. Same header, card shape, border and type scale as Settings, so the
 * four styles carry over.
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    // Tests pass a loader that does not suspend, so the list lands on the composition thread.
    load: suspend (Context) -> OpenSourceLicenses.Notices = {
        withContext(Dispatchers.IO) { OpenSourceLicenses.load(it) }
    },
) {
    val context = LocalContext.current
    val style = LocalAppStyle.current
    val notices by produceState<OpenSourceLicenses.Notices?>(null) {
        value = load(context.applicationContext)
    }
    // One open card at a time; survives rotation like the Settings flag does.
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    val cardShape = RoundedCornerShape(style.cardCorner)
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(cardShape)
        .background(MaterialTheme.colorScheme.surface)
        .then(
            if (style.outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, cardShape)
            else Modifier
        )
    val colModifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()

    LazyColumn(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = colModifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        item {
            val title = stringResource(R.string.open_source_licenses)
            if (style.bigHeader) {
                Text(
                    text = title,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = colModifier.padding(horizontal = 16.dp).padding(top = 26.dp, bottom = 40.dp)
                )
            } else {
                Text(
                    text = if (style.upperCaseLabels) title.uppercase() else title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = style.clockFont,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = colModifier.padding(start = 24.dp, end = 16.dp, bottom = 14.dp)
                )
            }
        }
        item {
            // Ducky's own license first: Apache 2.0, opening to the NOTICE lines, then the text.
            LicenseCard(
                name = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.licenses_own_license),
                header = stringResource(R.string.licenses_own_notice),
                licenses = listOfNotNull(notices?.own),
                expanded = expandedId == OWN_CARD_ID,
                onToggle = { expandedId = if (expandedId == OWN_CARD_ID) null else OWN_CARD_ID },
                modifier = colModifier.padding(horizontal = 16.dp).then(cardModifier)
            )
        }
        item {
            Text(
                text = stringResource(R.string.licenses_intro),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = colModifier.padding(horizontal = 34.dp).padding(top = 8.dp)
            )
        }
        item {
            Column(modifier = colModifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                SectionLabel(stringResource(R.string.licenses_section_libraries))
            }
        }
        items(notices?.libraries.orEmpty(), key = { it.id }) { lib ->
            val licenseNames = lib.licenses.joinToString(", ") { it.name }
                .ifEmpty { stringResource(R.string.licenses_unknown) }
            LicenseCard(
                name = lib.name,
                subtitle = if (lib.version.isBlank()) licenseNames
                else stringResource(R.string.licenses_library_subtitle, lib.version, licenseNames),
                header = lib.id,
                licenses = lib.licenses,
                expanded = expandedId == lib.id,
                onToggle = { expandedId = if (expandedId == lib.id) null else lib.id },
                modifier = colModifier.padding(horizontal = 16.dp).then(cardModifier)
            )
        }
        item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
    }
}

/** Ducky's own card has no Maven id, so it can't collide with a library's key. */
private const val OWN_CARD_ID = "ducky"

/**
 * One card: [name] and [subtitle]; a tap expands it to [header] (a library's Maven id, or
 * Ducky's NOTICE lines) and the full text of each license.
 */
@Composable
private fun LicenseCard(
    name: String,
    subtitle: String,
    header: String,
    licenses: List<OpenSourceLicenses.License>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                Text(
                    text = header,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                licenses.forEach { license ->
                    Text(
                        text = license.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                    )
                    Text(
                        text = OpenSourceLicenses.reflow(license.text),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
