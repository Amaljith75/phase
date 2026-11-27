package app.phase.stem.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Open-source notices required by the bundled libraries' licences. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("licenses.txt").bufferedReader().use { it.readLines() }
        }
    }
    Column(Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            Text("OPEN-SOURCE LICENCES", style = PhaseType.label, color = PhaseColors.Chrome)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(lines) { l ->
                Text(l, style = PhaseType.body.copy(fontSize = 11.sp, lineHeight = 15.sp), color = PhaseColors.Mist)
            }
        }
    }
}
