package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AppTheme

/** Lets the user pick which tool a Quick Tool widget opens, with a live preview. */
class QuickToolConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContent {
            AppTheme {
                var chosen by remember { mutableStateOf(QuickToolPrefs.get(this, widgetId)) }
                Scaffold { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.wi_pdf), null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Quick Tool widget", style = MaterialTheme.typography.titleLarge)
                                Text("Choose what this shortcut opens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        // Live preview of the widget
                        val t = WidgetActions.tool(chosen)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(
                                Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                            ) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(tileColor(t.tile)), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(t.icon), null, tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(t.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        LazyVerticalGrid(GridCells.Adaptive(92.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(WidgetActions.tools) { tool ->
                                val sel = tool.id == chosen
                                Column(
                                    Modifier.clip(RoundedCornerShape(20.dp))
                                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { chosen = tool.id }.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(tileColor(tool.tile)), contentAlignment = Alignment.Center) {
                                        Icon(painterResource(tool.icon), null, tint = Color.White, modifier = Modifier.size(24.dp))
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(tool.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                        Button(
                            onClick = {
                                QuickToolPrefs.set(this@QuickToolConfigActivity, widgetId, chosen)
                                QuickToolWidgetProvider.render(this@QuickToolConfigActivity, AppWidgetManager.getInstance(this@QuickToolConfigActivity), widgetId, chosen)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)
                        ) { Text("Add widget") }
                    }
                }
            }
        }
    }

    private fun tileColor(res: Int): Color = when (res) {
        R.drawable.wt_blue -> Color(0xFF1E88E5); R.drawable.wt_teal -> Color(0xFF00897B)
        R.drawable.wt_violet -> Color(0xFF7E57C2); R.drawable.wt_green -> Color(0xFF43A047)
        R.drawable.wt_pink -> Color(0xFFD81B60); R.drawable.wt_cyan -> Color(0xFF00ACC1)
        R.drawable.wt_orange -> Color(0xFFFB8C00); R.drawable.wt_red -> Color(0xFFE53935)
        R.drawable.wt_indigo -> Color(0xFF3949AB); R.drawable.wt_amber -> Color(0xFFFFB300)
        else -> Color(0xFF546E7A)
    }
}
