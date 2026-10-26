package app.orbit.vpn.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(val packageName: String, val label: String)

/** Installed app labels and icons, cached for the process lifetime. */
object Apps {
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun label(context: Context, packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? = icons[packageName] ?: runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
    }.getOrNull()?.also { icons[packageName] = it }

    /** Apps with a launcher entry, sorted by name; excludes this app. */
    suspend fun launchable(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { AppEntry(it, label(context, it)) }
            .sortedBy { it.label.lowercase() }
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { Apps.icon(context, packageName, px) }
    }.value
    if (bitmap != null) {
        Image(bitmap, null, modifier.size(size))
    } else {
        IconTile(Icons.Default.Android, size = size)
    }
}
