package com.merrimentlabs.ducky.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.OpenSourceLicenses
import com.merrimentlabs.ducky.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > About > Open-source notices, in the family's shape: Ducky's own row first, then a
 * sentence, then one row per component (its name over "version · license"). A row opens a page
 * of selectable text: Ducky's NOTICE lines or the component's Maven id, then each license in
 * full. The frame and the rows are Settings' (SettingsMetrics), so the four styles carry over.
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
    val notices by produceState<OpenSourceLicenses.Notices?>(null) {
        value = load(context.applicationContext)
    }
    // The open page, by row id; survives rotation like the Settings flag does.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val own = notices?.own
    val ownPage = LicensePage(
        title = stringResource(R.string.app_name),
        header = stringResource(R.string.licenses_own_notice),
        licenses = listOfNotNull(own),
    )
    val page = when (val id = openId) {
        null -> null
        OWN_ROW_ID -> ownPage
        else -> notices?.libraries?.firstOrNull { it.id == id }?.let { LicensePage(it.name, it.id, it.licenses) }
    }
    if (page != null) {
        BackHandler { openId = null }
        LicenseTextPage(page, onBack = { openId = null })
    } else {
        LicenseList(notices, onOpen = { openId = it }, onBack = onBack)
    }
}

/** Ducky's own row has no Maven id, so it can't collide with a library's key. */
private const val OWN_ROW_ID = "ducky"

/** What a license page shows: its [title], a [header] line, then each license in full. */
private class LicensePage(val title: String, val header: String, val licenses: List<OpenSourceLicenses.License>)

/** The house page frame: a centered column at most 720dp, the house side margins. */
@Composable
private fun HouseColumn(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val colModifier = Modifier
            .widthIn(max = SettingsMetrics.COLUMN_MAX)
            .fillMaxWidth()
            .padding(horizontal = SettingsMetrics.side(maxWidth))
        content(colModifier)
    }
}

@Composable
private fun LicenseList(
    notices: OpenSourceLicenses.Notices?,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val style = LocalAppStyle.current
    val corner = style.cardCorner
    HouseColumn { colModifier ->
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowTopInset()
                .navigationBarsPadding()
        ) {
            item {
                TopBar(
                    title = stringResource(R.string.open_source_licenses),
                    onBack = onBack,
                    modifier = colModifier.padding(top = SettingsMetrics.PAGE_TOP),
                )
            }
            item {
                Column(modifier = colModifier.padding(top = SettingsMetrics.LABEL_TOP)) {
                    LicenseRow(
                        name = stringResource(R.string.app_name),
                        subtitle = stringResource(R.string.licenses_own_license),
                        shape = RoundedCornerShape(corner),
                        onClick = { onOpen(OWN_ROW_ID) },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = colModifier.padding(
                        start = SettingsMetrics.LABEL_SIDE,
                        end = SettingsMetrics.LABEL_SIDE,
                        top = 12.dp,
                    )
                )
            }
            item {
                Column(modifier = colModifier) {
                    SectionLabel(stringResource(R.string.licenses_section_libraries))
                }
            }
            val libraries = notices?.libraries.orEmpty()
            itemsIndexed(libraries, key = { _, lib -> lib.id }) { index, lib ->
                val licenseNames = lib.licenses.joinToString(", ") { it.name }
                    .ifEmpty { stringResource(R.string.licenses_unknown) }
                Column(modifier = colModifier) {
                    if (index > 0) CardDividerOnSurface()
                    LicenseRow(
                        name = lib.name,
                        subtitle = if (lib.version.isBlank()) licenseNames
                        else stringResource(R.string.licenses_library_subtitle, lib.version, licenseNames),
                        shape = cardSlice(corner, first = index == 0, last = index == libraries.lastIndex),
                        onClick = { onOpen(lib.id) },
                    )
                }
            }
            item { Column(modifier = Modifier.padding(bottom = SettingsMetrics.PAGE_BOTTOM)) {} }
        }
    }
}

/** A row's share of one card drawn across several list items: rounded only at the card's ends. */
private fun cardSlice(corner: Dp, first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
    bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp,
)

/** The hairline between two rows of a card drawn across list items, on the card's color. */
@Composable
private fun CardDividerOnSurface() {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        SettingsDivider()
    }
}

/** One row of the list, in its card [shape]: the name over its version and license, then ›. */
@Composable
private fun LicenseRow(name: String, subtitle: String, shape: Shape, onClick: () -> Unit) {
    val style = LocalAppStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (style.outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .focusRing(RectangleShape, focusRingColor())
            .clickable(role = Role.Button, onClick = onClick)
            .rowPadding()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(name)
            RowSubtitle(subtitle)
        }
        RowChevron()
    }
}

/** A license page: the house top bar with the component's name, then text a person can select. */
@Composable
private fun LicenseTextPage(page: LicensePage, onBack: () -> Unit) {
    HouseColumn { colModifier ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowTopInset()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(top = SettingsMetrics.PAGE_TOP, bottom = SettingsMetrics.PAGE_BOTTOM)
        ) {
            TopBar(title = page.title, onBack = onBack, modifier = colModifier)
            SelectionContainer(modifier = colModifier.padding(horizontal = SettingsMetrics.LABEL_SIDE)) {
                Column {
                    Text(
                        text = page.header,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                    page.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        )
                        Text(
                            text = OpenSourceLicenses.reflow(license.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
