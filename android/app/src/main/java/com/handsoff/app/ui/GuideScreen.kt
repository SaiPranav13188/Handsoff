package com.handsoff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class GuideItem(val icon: String, val title: String, val text: String)

private data class GuideSection(val title: String, val items: List<GuideItem>)

private val GUIDE = listOf(
    GuideSection(
        "Get started",
        listOf(
            GuideItem(
                "👊",
                "Pair once",
                "Open Handsoff on both phones and tap 👊 Fist-bump pair on each. Then punch your fist toward your camera at the same moment — or tap the two phones together. You can also tap Pair under Nearby and check that both phones show the same code.",
            ),
            GuideItem(
                "🔗",
                "Stays paired",
                "Paired phones reconnect by themselves whenever they're near each other. No Wi-Fi network or internet needed — just keep Bluetooth on.",
            ),
            GuideItem(
                "📥",
                "Receiving",
                "Works even when the app is closed. Items are saved to your Gallery in the Handsoff album (other files in Downloads/Handsoff), and a notification tells you who sent what.",
            ),
        ),
    ),
    GuideSection(
        "Pick what to send",
        listOf(
            GuideItem("☝️", "Show fingers to select", "With nothing picked, hold up 1–5 fingers for a moment: your newest 1–5 photos or videos (the top of your Gallery) are selected. A bar fills while you hold. Then make a fist and open your hand to send."),
            GuideItem("🖼️", "From the Gallery", "Select photos in your Gallery or Files app, tap Share, then Handsoff. Or use the Photos & videos and Files buttons."),
            GuideItem("↔️", "Swipe to browse", "With several items picked, swipe your hand sideways in the air to flip through them."),
            GuideItem("✕", "Remove any photo", "Below the stack there's a row of everything you selected — tap ✕ on any photo to drop just that one. Or 👌 pinch (thumb touching index finger) to remove the one on top."),
        ),
    ),
    GuideSection(
        "Send",
        listOf(
            GuideItem("✊", "Grab", "Make a fist (straight after showing fingers is fine): the stack shrinks — you're holding it."),
            GuideItem("✋", "Let go", "Open your hand again and the stack flies off to the other phone."),
            GuideItem("🔢", "Choose the phone with fingers", "With items picked, hold up 1–4 fingers to choose phone #1–#4 (the numbers on the phone chips)."),
            GuideItem("🎯", "Or flick it", "While opening your hand, flick it ← → or ↑ to send to phone #1, #2 or #3 — like throwing it."),
            GuideItem("👆", "No camera?", "Tap the stack to grab it, tap again to send — or press the Send button."),
        ),
    ),
    GuideSection(
        "After sending and receiving",
        listOf(
            GuideItem("👋", "Wave to unsend", "Changed your mind? Wave your hand side to side within 5 seconds of sending (or tap Undo). If it already arrived, it's removed from the other phone's Gallery."),
            GuideItem("👍", "React", "After receiving, show a 👍 or ✌️ to your camera (within 2 minutes). The sender gets \"reacted 👍\"."),
        ),
    ),
    GuideSection(
        "More",
        listOf(
            GuideItem("📋", "Clipboard teleport", "Copy a link, address or OTP. With nothing picked, make the 👌 pinch: it lands on the other phone's clipboard, and links open from the notification."),
            GuideItem("🏖️", "Trip mode", "Turn it on under My phones: every new photo you take goes to all your paired phones automatically — great on trips with no network."),
            GuideItem("⏳", "Phone not nearby?", "Items wait on your phone and go automatically the next time the other phone is in range."),
        ),
    ),
    GuideSection(
        "Tips for good tracking",
        listOf(
            GuideItem("📏", "Distance", "Keep your hand 30–60 cm from the front camera, with the whole hand and wrist in view."),
            GuideItem("💡", "Light", "Face a light source; a bright window behind you makes your hand hard to see."),
            GuideItem("⏸️", "Hold still", "Signs and finger counts need a short, steady hold — watch the bar fill up."),
        ),
    ),
)

/** Full-screen "How to use" guide; shown on first launch and from the ? button. */
@Composable
fun GuideScreen(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog doesn't inherit the screen's text colour, so set it here.
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D12), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("How to use Handsoff", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    Text("Send photos between phones with your hands", color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("⚡ Fastest send — no screen touches", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Open Handsoff → show 3 fingers → make a fist → open your hand.", fontSize = 14.sp)
                        Text("Your latest 3 photos land in the other phone's Gallery.", color = Muted, fontSize = 13.sp)
                    }
                }
                GUIDE.forEach { section ->
                    item {
                        Text(
                            section.title.uppercase(),
                            Modifier.padding(top = 10.dp),
                            color = Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }
                    items(section.items) { GuideRow(it) }
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Got it") }
        }
        }
    }
}

@Composable
private fun GuideRow(item: GuideItem) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(item.icon, fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(item.text, color = Color(0xFFC9CED9), fontSize = 13.5.sp, lineHeight = 19.sp)
        }
    }
}
