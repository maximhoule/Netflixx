package com.maxim.marquee.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.AppModel
import com.maxim.marquee.PhoneSetup

@Composable
fun QrCode(text: String, size: Dp) {
    val bmp = remember(text) { PhoneSetup.qr(text) }
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
        Image(bmp.asImageBitmap(), contentDescription = text, modifier = Modifier.fillMaxSize())
    }
}

/** Starts the local setup page while this composable is on screen; returns its address. */
@Composable
fun rememberPhoneSetup(model: AppModel): String? {
    var url by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(attempt) {
        val server = PhoneSetup(ctx.applicationContext) { form -> model.applySetup(form) }
        url = server.start()
        onDispose { server.stop() }
    }
    // Network still connecting when the screen opened: try again every couple of seconds.
    LaunchedEffect(url, attempt) {
        if (url == null && attempt < 30) {
            kotlinx.coroutines.delay(2_000)
            attempt++
        }
    }
    return url
}

@Composable
fun PhoneSetupPanel(url: String?, title: String = "Set up from your phone") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (url != null) {
            QrCode(url, 220.dp)
            Spacer(Modifier.height(12.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(url, color = C.Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Text("Phone must be on the same Wi-Fi", color = C.Dim, fontSize = 12.sp)
        } else {
            Text("Connect the TV to your network to set up from a phone", color = C.Dim, fontSize = 14.sp)
        }
    }
}

@Composable
fun PhoneSetupScreen(model: AppModel) {
    val url = rememberPhoneSetup(model)
    Row(Modifier.fillMaxSize().background(C.Bg).padding(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 48.dp)) {
            Text("Set up from your phone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(
                "Scan the code with your phone's camera. A page opens where you can type your Stremio sign-in, " +
                    "your Emby server, Trakt keys and a TMDB key: much easier than the remote.",
                color = C.Dim, fontSize = 16.sp, lineHeight = 23.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Fill in only what you need. Results appear on your phone. Press Back when you're done.",
                color = C.Dim, fontSize = 16.sp, lineHeight = 23.sp,
            )
        }
        PhoneSetupPanel(url)
    }
}

@Composable
fun TraktActivateScreen(model: AppModel) {
    LaunchedEffect(Unit) { if (model.traktCode == null) model.startTraktAuth() }
    DisposableEffect(Unit) { onDispose { model.cancelTraktAuth() } }
    val dc = model.traktCode
    Row(Modifier.fillMaxSize().background(C.Bg).padding(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 48.dp)) {
            Text("Connect Trakt", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (dc != null) {
                Text("1. On your phone, go to", color = C.Dim, fontSize = 17.sp)
                Text(dc.url, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text("2. Enter this code", color = C.Dim, fontSize = 17.sp)
                Text(
                    dc.userCode, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, letterSpacing = 6.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text("3. Approve Marquee. This screen closes by itself.", color = C.Dim, fontSize = 17.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = model.traktStatus
                if (status?.startsWith("Waiting") == true || status?.startsWith("Getting") == true) {
                    CircularProgressIndicator(color = C.Red, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                }
                status?.let { Text(it, color = Color.White, fontSize = 16.sp) }
            }
        }
        if (dc != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(dc.url, 220.dp)
                Spacer(Modifier.height(10.dp))
                Text("Scan to open ${dc.url.removePrefix("https://")}", color = C.Dim, fontSize = 13.sp)
            }
        }
    }
}
