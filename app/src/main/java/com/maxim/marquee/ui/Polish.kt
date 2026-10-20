package com.maxim.marquee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.AppModel
import com.maxim.marquee.ProfileGate
import com.maxim.marquee.Screen
import com.maxim.marquee.data.MetaItem
import com.maxim.marquee.data.Profile
import com.maxim.marquee.data.Store
import com.maxim.marquee.data.Tmdb

private fun focusSafely(fr: FocusRequester) {
    try {
        fr.requestFocus()
    } catch (e: Exception) {
        // not attached yet
    }
}

/** Age rating + logo from TMDB for a title, loaded once and cached (null without a TMDB key). */
@Composable
fun rememberExtras(m: MetaItem): Tmdb.Extras? {
    var e by remember(m.id) { mutableStateOf(Tmdb.cachedExtras(m.id.substringBefore(':'))) }
    LaunchedEffect(m.id) {
        if (e == null && Tmdb.configured && m.id.startsWith("tt")) e = Tmdb.extras(m.type, m.id)
    }
    return e
}

// ---------------------------------------------------------------- avatars

@Composable
fun Avatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(p.color), RoundedCornerShape(size * 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontSize = (size.value * 0.48f).sp,
            fontWeight = FontWeight.Black,
        )
    }
}

// ---------------------------------------------------------------- "Who's watching?"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfilesScreen(model: AppModel) {
    val manage = model.profileGate == ProfileGate.MANAGE
    val profiles = model.profiles
    val requesters = remember(profiles.size) { List(profiles.size + 1) { FocusRequester() } }
    LaunchedEffect(manage, profiles.size) {
        withFrameNanos { }
        val i = profiles.indexOfFirst { it.id == Store.activeProfileId }.coerceAtLeast(0)
        focusSafely(requesters[i])
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (manage) "Manage Profiles" else "Who's watching?",
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(40.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(30.dp),
                modifier = Modifier.focusGroup(),
            ) {
                profiles.forEachIndexed { i, p ->
                    ProfileTile(p.name, requesters[i], editing = manage, onClick = {
                        if (manage) model.profileEdit = p else model.pickProfile(p)
                    }) { Avatar(p, 140.dp) }
                }
                if (profiles.size < 5) {
                    ProfileTile("Add Profile", requesters[profiles.size], editing = false, onClick = {
                        val used = profiles.map { it.color }.toSet()
                        val color = Store.profileColors.firstOrNull { it !in used } ?: Store.profileColors[profiles.size % Store.profileColors.size]
                        model.profileEdit = Profile(-1, "", color)
                    }) {
                        Box(Modifier.size(140.dp).border(2.dp, Color(0xFF555555), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, tint = Color(0xFFAAAAAA), modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(44.dp))
            ActionButton(
                text = if (manage) "Done" else "Manage Profiles",
                icon = if (manage) null else Icons.Filled.Edit,
                onClick = { model.profileGate = if (manage) ProfileGate.PICK else ProfileGate.MANAGE },
            )
        }
    }
}

@Composable
private fun ProfileTile(name: String, fr: FocusRequester, editing: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
        Tile(
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.1f,
            focusRequester = fr,
            onFocusChange = { focused = it },
            onClick = onClick,
        ) {
            picture()
            if (editing) {
                Box(Modifier.size(140.dp).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, "Edit", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            name, color = if (focused) Color.White else C.Dim, fontSize = 18.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ProfileEditScreen(model: AppModel) {
    val p = model.profileEdit ?: return
    val isNew = p.id < 0
    var name by remember(p) { mutableStateOf(p.name) }
    var color by remember(p) { mutableIntStateOf(p.color) }
    val focus = LocalFocusManager.current
    val nameFr = remember { FocusRequester() }
    LaunchedEffect(p) { withFrameNanos { }; focusSafely(nameFr) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF666666),
        focusedLabelColor = Color.White, unfocusedLabelColor = C.Dim, cursorColor = C.Red,
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp)) {
            Text(if (isNew) "Add Profile" else "Edit Profile", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            if (isNew) {
                Text(
                    "Each profile has its own Stremio sign-in, Continue Watching, My List, Trakt and settings.",
                    color = C.Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Top) {
                Avatar(p.copy(name = name.ifBlank { "?" }, color = color), 130.dp)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(20) }, singleLine = true,
                        label = { Text("Name") }, colors = fieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFr),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Colour", color = C.Dim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Store.profileColors.forEach { c ->
                            Tile(shape = CircleShape, focusedScale = 1.15f, onClick = { color = c }) {
                                Box(
                                    Modifier.size(40.dp).background(Color(c), CircleShape)
                                        .then(if (c == color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionButton("Save", onClick = { model.saveProfile(name, color) })
                ActionButton("Cancel", onClick = { model.profileEdit = null })
                if (!isNew && model.profiles.size > 1) {
                    ActionButton("Delete Profile", onClick = { model.deleteProfile(p.id) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- copy addons into a new profile

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CopyAddonsOverlay(model: AppModel, from: Profile) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(from) { withFrameNanos { }; focusSafely(fr) }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .background(Color(0xFF181818), RoundedCornerShape(12.dp)).padding(28.dp),
        ) {
            Text("Copy ${from.name}'s addons?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Marquee can copy ${from.name}'s Stremio addons (Duck Streams and the rest) into this profile's Stremio account, " +
                    "replacing the addons it has now. ${from.name}'s account isn't changed.",
                color = C.Dim, fontSize = 16.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Copy addons", focusRequester = fr, onClick = { model.copyAddonsFrom(from) })
                ActionButton("Not now", onClick = { model.offerCopyFrom = null })
            }
        }
    }
}

// ---------------------------------------------------------------- when something goes wrong

@Composable
fun ProblemScreen(model: AppModel, p: Screen.Problem) {
    val name = p.req?.let { r -> r.name + (r.episode?.let { " · ${it.substringBefore("  ")}" } ?: "") }
    val (title, body) = when (p.kind) {
        Screen.Problem.Kind.OFFLINE -> "You're offline" to
            "Marquee can't reach the internet. Check that the TV is connected to Wi-Fi or Ethernet, then try again."
        Screen.Problem.Kind.NO_SOURCES -> "No sources found" to
            "None of your addons found a playable copy${name?.let { " of $it" } ?: ""}. It may be very new, or your stream addon may be " +
            "having trouble right now. Try again in a little while, or look through the sources yourself."
        Screen.Problem.Kind.SERVICE -> "Your streaming service needs attention" to
            "Your stream addon reported a problem. This usually means the debrid subscription has expired or its API key " +
            "needs updating. Check your debrid account, then your Duck Streams (AIOStreams) settings."
        Screen.Problem.Kind.PLAYBACK -> "This video won't play" to
            "Marquee tried the best sources${name?.let { " for $it" } ?: ""} but none of them would start. " +
            "Try again, or choose a different source."
    }
    val tryFr = remember { FocusRequester() }
    LaunchedEffect(p) { withFrameNanos { }; focusSafely(tryFr) }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(p.req?.background, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight())
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 72.dp).width(640.dp)) {
            Text(title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
            Spacer(Modifier.height(14.dp))
            Text(body, color = Color(0xFFDDDDDD), fontSize = 18.sp, lineHeight = 26.sp)
            p.message?.takeIf { it.isNotBlank() }?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text("What the service said", color = C.Dim, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    msg, color = Color(0xFFBBBBBB), fontSize = 14.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(6.dp)).padding(12.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Try again", icon = Icons.Filled.Refresh, focusRequester = tryFr, onClick = { model.retry(p) })
                val req = p.req
                if (req != null && p.kind != Screen.Problem.Kind.OFFLINE) {
                    ActionButton("Choose a source", icon = Icons.AutoMirrored.Filled.List, onClick = {
                        model.back(); model.play(req, chooseSource = true)
                    })
                }
                ActionButton("Back", onClick = { model.back() })
            }
        }
    }
}

/** Small notice at the top while the connection is down. */
@Composable
fun OfflineBanner() {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.background(Color(0xF02A2A2A), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(Color(0xFFF5B50E), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("No internet connection · reconnecting automatically", color = Color.White, fontSize = 15.sp)
        }
    }
}

/** Shown on Home when the Stremio addon list couldn't be loaded at all. */
@Composable
fun AddonErrorPanel(model: AppModel, error: String) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(error) { withFrameNanos { }; focusSafely(fr) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(560.dp)) {
            Text(
                if (model.online) "Couldn't load your Stremio addons" else "You're offline",
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (model.online) "Stremio didn't answer ($error). It's usually temporary." else
                    "Connect the TV to the internet. Marquee reloads by itself when the connection is back.",
                color = C.Dim, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            ActionButton("Try again", icon = Icons.Filled.Refresh, focusRequester = fr, onClick = { model.refreshAll() })
        }
    }
}
