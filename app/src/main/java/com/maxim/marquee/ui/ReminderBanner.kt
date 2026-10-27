package com.maxim.marquee.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.AppModel

/** "New episodes" banner in the corner when you open the app (it goes away by itself). */
@Composable
fun ReminderBanner(r: AppModel.Reminder, modifier: Modifier = Modifier) {
    var shown by remember(r) { mutableStateOf(false) }
    LaunchedEffect(r) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        modifier = modifier.padding(end = 40.dp, bottom = 36.dp),
    ) {
        Column(
            Modifier
                .width(380.dp)
                .background(Color(0xF0141414), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DateRange, null, tint = C.Red, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (r.lines.size + r.more == 1) "NEW EPISODE" else "NEW EPISODES",
                    color = C.Red, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            r.lines.forEach {
                Text(
                    it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                (if (r.more > 0) "+${r.more} more · " else "") + "See them all in Calendar",
                color = C.Dim, fontSize = 12.sp,
            )
        }
    }
}
