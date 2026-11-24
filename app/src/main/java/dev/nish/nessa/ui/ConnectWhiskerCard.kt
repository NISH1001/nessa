package dev.nish.nessa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nish.nessa.ui.settings.ConnectionsViewModel
import dev.nish.nessa.ui.settings.SignInForm
import dev.nish.nessa.whisker.ConnectionStatus

/** Shown wherever Nessa needs Whisker but isn't signed in. Signs in right here, no trip to Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWhiskerCard() {
    val container = appContainer()
    val vm: ConnectionsViewModel = viewModel(
        key = "connect-card",
        factory = viewModelFactory { initializer { ConnectionsViewModel(container.session, container.api, container.robotChoice) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val again = ui.status == ConnectionStatus.NeedsReconnect
    LaunchedEffect(ui.status) { if (ui.status == ConnectionStatus.Connected) showSheet = false }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (again) "Sign in to Whisker again" else "Connect your Whisker account",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (again) "Your saved sign-in stopped working."
                else "Needed to see and control the litter box.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { vm.clearError(); showSheet = true }) { Text(if (again) "Sign in" else "Connect") }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SignInForm(busy = ui.busy, error = ui.error, onConnect = { email, password -> vm.signIn(email, password) })
        }
    }
}
