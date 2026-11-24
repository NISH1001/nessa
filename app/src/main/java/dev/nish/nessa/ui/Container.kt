package dev.nish.nessa.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.nish.nessa.AppContainer
import dev.nish.nessa.NessaApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as NessaApp).container
