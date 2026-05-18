package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Dialog
import dev.nitka.nodewire.ui.components.DialogContent
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Modal "Save as…" prompt. [initial] pre-populates the field with the
 * current graph name. [onConfirm] receives the trimmed-but-not-sanitised
 * input (GraphFiles re-sanitises on write).
 */
@Composable
fun SaveAsDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        DialogContent(modifier = Modifier.width(280)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8),
            ) {
                Text("Save graph as", style = NwTheme.typography.title)
                TextInput(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    placeholder = "Graph name",
                    onValueChange = { name = it },
                    onSubmit = {
                        if (name.isNotBlank()) {
                            onConfirm(name)
                            onDismiss()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedByEnd(NwTheme.dimens.space6),
                ) {
                    Button(onClick = onDismiss, style = ButtonDefaults.ghost()) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onConfirm(name)
                                onDismiss()
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("Save") }
                }
            }
        }
    }
}
