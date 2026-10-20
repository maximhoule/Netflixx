package com.maxim.marquee

import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.maxim.marquee.ui.C
import com.maxim.marquee.ui.CardMenuOverlay
import com.maxim.marquee.ui.DetailScreen
import com.maxim.marquee.ui.LoadingOverlay
import com.maxim.marquee.ui.LoginScreen
import com.maxim.marquee.ui.MainShell
import com.maxim.marquee.ui.StreamsScreen

class MainActivity : ComponentActivity() {
    lateinit var model: AppModel

    val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        model.onPlayerResult(res.data)
    }

    val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        model.onVoiceResult(res.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(this)
        model.supportsDV = try {
            @Suppress("DEPRECATION")
            val caps = if (Build.VERSION.SDK_INT >= 24) windowManager.defaultDisplay.hdrCapabilities else null
            caps?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        } catch (e: Exception) {
            false
        }
        setContent { AppRoot(model) { finish() } }
        model.start()
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
    }
}

@Composable
fun AppRoot(model: AppModel, exit: () -> Unit) {
    val railRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Red, background = C.Bg, surface = C.Surface)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(C.Bg)
                // While a source is being found, swallow remote presses (Back still cancels).
                .onPreviewKeyEvent { model.loading != null && it.key != Key.Back },
        ) {
            if (!model.loggedIn) {
                LoginScreen(model)
            } else {
                val top = model.stack.lastOrNull()
                key(top) {
                    when (top) {
                        null -> MainShell(model, railRequesters)
                        is Screen.Detail -> DetailScreen(model, top)
                        is Screen.Streams -> StreamsScreen(model, top)
                    }
                }
                // Back: close the top screen; on the home screens go to the menu first, then exit.
                BackHandler {
                    when {
                        model.back() -> Unit
                        !model.railFocused -> {
                            try {
                                railRequesters.getValue(model.tab).requestFocus()
                            } catch (e: Exception) {
                                exit()
                            }
                        }
                        else -> exit()
                    }
                }
            }
            if (model.cardMenu != null) {
                CardMenuOverlay(model)
                BackHandler { model.closeCardMenu() }
            }
            model.loading?.let { req ->
                LoadingOverlay(req)
                BackHandler { model.cancelLoading() }
            }
        }
    }
}

