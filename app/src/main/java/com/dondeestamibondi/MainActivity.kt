package com.dondeestamibondi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
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
import org.osmdroid.config.Configuration
import com.dondeestamibondi.ui.BondiScreen
import com.dondeestamibondi.ui.TrenScreen
import com.dondeestamibondi.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSMDroid needs a non-default User-Agent or OSM tile servers reject requests.
        Configuration.getInstance().userAgentValue = packageName
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
        when (tabs[selected]) {
            Tab.BONDIS -> BondiScreen(Modifier.padding(inner))
            Tab.TRENES -> TrenScreen(Modifier.padding(inner))
        }
    }
}
