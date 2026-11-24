package dev.nish.nessa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nish.nessa.ui.NessaRoot
import dev.nish.nessa.ui.theme.NessaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NessaTheme { NessaRoot() } }
    }
}
