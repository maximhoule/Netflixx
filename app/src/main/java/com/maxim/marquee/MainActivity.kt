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
import com.maxim.marquee.ui.EmbyLoginScreen
import com.maxim.marquee.ui.LoadingOverlay
import com.maxim.marquee.ui.LoginScreen
import com.maxim.marquee.ui.MainShell
import com.maxim.marquee.ui.StreamsScreen
import com.maxim.marquee.ui.UpdateBanner
import com.maxim.marquee.ui.OfflineBanner
import com.maxim.marquee.ui.ProblemScreen
import com.maxim.marquee.ui.ProfilesScreen
import com.maxim.marquee.ui.ProfileEditScreen
import com.maxim.marquee.ui.CopyAddonsOverlay

class MainActivity : ComponentActivity() {
    companion object {
        /** Set once someone has picked a profile in this app session, so switching doesn't ask again. */
        var profileChosen = false
    }

    lateinit var model: AppModel
    private var unwatchNetwork: () -> Unit = {}

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
        try {
            @Suppress("DEPRECATION")
            val modes = windowManager.defaultDisplay.supportedModes
            val tallest = modes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 2160
            model.displayMaxHeight = if (tallest >= 2000) 2160 else if (tallest >= 1400) 1440 else 1080
        } catch (e: Exception) {
            // keep default
        }
        model.av1Hardware = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", true) } &&
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
        } catch (e: Exception) {
            false
        }
        setContent { AppRoot(model) { finish() } }
        // "Who's watching?" when the app opens and there's more than one profile.
        if (!profileChosen && com.maxim.marquee.data.Store.profiles.size > 1) {
            model.profileGate = ProfileGate.PICK
        } else {
            model.start()
        }
        profileChosen = true
        unwatchNetwork = com.maxim.marquee.data.Net.watch(this) { up -> runOnUiThread { model.onNetwork(up) } }
    }

    override fun onDestroy() {
        // Leaving the app (not just switching profile): ask "Who's watching?" next time.
        if (isFinishing) profileChosen = false
        unwatchNetwork()
        model.destroy()
        super.onDestroy()
    }

    /** True while OK is physically held down. */
    var okHeld = false
    /** Set when a long-press opened a menu: the release of that same press must not click anything. */
    var swallowOkUp = false

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val ok = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (ok) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okHeld = true
                    swallowOkUp = false
                } else if (swallowOkUp) {
                    return true
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                okHeld = false
                if (swallowOkUp) {
                    swallowOkUp = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    var isForeground = false

    override fun onResume() {
        super.onResume()
        isForeground = true
        model.resumeTrailer()
        model.onResume()
        model.checkForUpdate()
    }

    override fun onPause() {
        isForeground = false
        model.pauseTrailer()
        model.onPause()
        super.onPause()
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
            if (model.profileGate != ProfileGate.NONE || model.profileEdit != null) {
                if (model.profileEdit != null) ProfileEditScreen(model) else ProfilesScreen(model)
                BackHandler { if (!model.profileBack()) exit() }
            } else if (!model.loggedIn) {
                LoginScreen(model)
                if (model.profiles.size > 1) BackHandler { model.openProfiles() }
            } else {
                val top = model.stack.lastOrNull()
                key(top) {
                    when (top) {
                        null -> MainShell(model, railRequesters)
                        is Screen.Detail -> DetailScreen(model, top)
                        is Screen.Streams -> StreamsScreen(model, top)
                        is Screen.EmbyLogin -> EmbyLoginScreen(model)
                        is Screen.PhoneSetup -> com.maxim.marquee.ui.PhoneSetupScreen(model)
                        is Screen.TraktActivate -> com.maxim.marquee.ui.TraktActivateScreen(model)
                        is Screen.Problem -> ProblemScreen(model, top)
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
            if (model.loggedIn && !model.online && model.profileGate == ProfileGate.NONE) OfflineBanner()
            model.offerCopyFrom?.let { p ->
                CopyAddonsOverlay(model, p)
                BackHandler { model.offerCopyFrom = null }
            }
            if (model.cardMenu != null) {
                CardMenuOverlay(model)
                BackHandler { model.closeCardMenu() }
            }
            model.loading?.let { req ->
                LoadingOverlay(req)
                BackHandler { model.cancelLoading() }
            }
            model.updateStatus?.let { UpdateBanner(it, model.updateProgress) }
        }
    }
}

