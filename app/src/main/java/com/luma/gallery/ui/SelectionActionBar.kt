package com.luma.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [label] is the short word under the icon; [description] is what a screen reader says for the action. */
internal class SelectionAction(val label: String, val description: String, val icon: ImageVector, val tag: String, val onClick: () -> Unit)

/**
 * What can be done with the selected items. It takes the floating navigation's place and shape while a selection
 * is active: the navigation has no use then, and words under the icons tell "add to album" from "move to folder".
 */
@Composable
internal fun SelectionActionBar(actions: List<SelectionAction>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 400.dp).fillMaxWidth().height(76.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant).testTag("selection-bar").padding(6.dp)) {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            actions.forEach { action ->
                Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).testTag(action.tag)
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .semantics { contentDescription = action.description }.padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(action.icon, null, tint = tint, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp))
                    // On a narrow screen or with large text, a long word shrinks a little rather than being cut.
                    Text(action.label, Modifier.clearAndSetSemantics {}, color = tint, lineHeight = 14.sp, maxLines = 1, softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp))
                }
            }
        }
    }
}
