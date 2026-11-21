package com.luma.gallery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.luma.gallery.R

/**
 * The privacy policy shipped with the app. It is the same HTML that is published for Google Play
 * (docs/privacy), so the in-app text and the store link never drift apart.
 */
@Composable
internal fun PrivacyPolicyPage(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = LocalGalleryDarkTheme.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        val context = LocalContext.current
        val asset = stringResource(R.string.privacy_policy_asset)
        val accent = MaterialTheme.colorScheme.primary
        val policy = remember(asset, accent) {
            val html = context.assets.open(asset).bufferedReader().use { it.readText() }
            policyText(html, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))
        }
        Surface(Modifier.fillMaxSize().testTag("privacy-policy"), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                        Text(stringResource(R.string.settings_privacy), Modifier.padding(start = 3.dp), style = MaterialTheme.typography.titleLarge)
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(policy, Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The policy's main content as styled text: the page title (shown in the header) and the language link are left out. */
internal fun policyText(html: String, links: TextLinkStyles): AnnotatedString {
    val main = html.substringAfter("<main>").substringBefore("</main>")
        .replace(Regex("<nav>.*?</nav>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<h1>.*?</h1>", RegexOption.DOT_MATCHES_ALL), "")
        // Blocks are separated by single line breaks; give each section an empty line above it.
        .replace("<h2>", "<br><h2>")
    return AnnotatedString.fromHtml(main.trim(), links)
}
