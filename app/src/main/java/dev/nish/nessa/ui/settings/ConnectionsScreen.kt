package dev.nish.nessa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nish.nessa.ui.ScreenBody
import dev.nish.nessa.ui.appContainer
import dev.nish.nessa.whisker.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen() {
    val container = appContainer()
    val vm: ConnectionsViewModel = viewModel(
        factory = viewModelFactory { initializer { ConnectionsViewModel(container.session, container.api, container.robotChoice) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val connected = ui.status == ConnectionStatus.Connected

    // Close the sign-in sheet once Whisker accepts the sign-in.
    LaunchedEffect(connected) { if (connected) showSheet = false }
    // The success message is a brief confirmation, not a permanent label.
    LaunchedEffect(ui.note) { if (ui.note != null) { kotlinx.coroutines.delay(4000); vm.clearNote() } }

    ScreenBody {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Whisker (Litter-Robot)", style = MaterialTheme.typography.titleMedium)
                Text(
                    ui.status.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.robots.size > 1) {
                    Text("Which litter box should Nessa look after?", style = MaterialTheme.typography.bodyMedium)
                    ui.robots.forEach { robot ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = robot.selected, enabled = !ui.busy, role = Role.RadioButton) { vm.selectRobot(robot.serial) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = robot.selected, onClick = null, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp))
                            Text(robot.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    ui.robotName?.let { Text("Looking after $it", style = MaterialTheme.typography.bodyMedium) }
                }
                ui.note?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!showSheet) ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (connected) {
                        OutlinedButton(onClick = { vm.test() }, enabled = !ui.busy) { Text(if (ui.busy) "Checking…" else "Test connection") }
                        TextButton(onClick = { confirmSignOut = true }, enabled = !ui.busy) { Text("Sign out") }
                    } else {
                        Button(onClick = { vm.clearError(); showSheet = true }) {
                            Text(if (ui.status == ConnectionStatus.NeedsReconnect) "Sign in again" else "Connect")
                        }
                    }
                    if (ui.busy && !showSheet) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SignInForm(busy = ui.busy, error = ui.error, onConnect = { email, password -> vm.signIn(email, password) })
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Whisker?") },
            text = { Text("Nessa will stop managing the litter box.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SignInForm(busy: Boolean, error: String?, onConnect: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: the password should not be written into saved instance state.
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect your Whisker account", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            supportingText = { Text(error ?: "Stored encrypted on this phone only.") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConnect(email, password) }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onConnect(email, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
        }
    }
}
