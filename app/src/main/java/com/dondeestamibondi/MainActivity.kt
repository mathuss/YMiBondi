package com.dondeestamibondi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.dondeestamibondi.data.UserPrefs
import org.osmdroid.config.Configuration
import com.dondeestamibondi.ui.BondiScreen
import com.dondeestamibondi.ui.TrenScreen
import com.dondeestamibondi.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSMDroid needs a non-default User-Agent or OSM tile servers reject requests.
        Configuration.getInstance().userAgentValue = packageName
        UserPrefs.init(this)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppScaffold()
            }
        }
    }
}

private enum class Tab(val label: String) { BONDIS("Bondis"), TRENES("Trenes") }

@androidx.compose.runtime.Composable
private fun AppScaffold() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toList() }
    // A tab is composed the first time it is opened and then kept alive (zero-sized when
    // hidden), so switching back doesn't lose the map, the line or the station.
    val opened = remember { mutableSetOf(0) }
    opened.add(selected)

    // Back from a non-default tab goes to the default tab instead of leaving the app;
    // from the default tab it falls through to the system's normal exit behavior.
    BackHandler(enabled = selected != 0) { selected = 0 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = if (tab == Tab.BONDIS)
                                    Icons.Filled.DirectionsBus else Icons.Filled.Train,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { inner ->
        // consumeWindowInsets: screens use imePadding(), which must only add what the
        // keyboard takes beyond the bottom bar that is already padded here.
        Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
            tabs.forEachIndexed { index, tab ->
                if (index !in opened) return@forEachIndexed
                val active = selected == index
                Box(if (active) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) {
                    when (tab) {
                        Tab.BONDIS -> BondiScreen(active = active)
                        Tab.TRENES -> TrenScreen(active = active)
                    }
                }
            }
        }
    }
}
