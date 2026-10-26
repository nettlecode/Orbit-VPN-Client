package app.orbit.vpn.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.vpn.R
import app.orbit.vpn.data.ServerSort
import app.orbit.vpn.model.ProxyNode
import app.orbit.vpn.model.Subscription
import io.nekohasekai.libbox.Libbox

private enum class ProfileFilter { ALL, SUBSCRIPTIONS, LOCAL, FAVORITES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(viewModel: MainViewModel, onScanQr: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latency by viewModel.latency.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var filter by rememberSaveable { mutableIntStateOf(ProfileFilter.ALL.ordinal) }
    var query by rememberSaveable { mutableStateOf("") }
    var addSheet by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var subscriptionDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProxyNode?>(null) }
    var pendingSubscriptionDelete by remember { mutableStateOf<Subscription?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    /** Title and link shown as a QR code. */
    var qr by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.action_share)

    val subscriptionIds = state.subscriptions.map { it.id }.toSet()
    fun matches(node: ProxyNode) = query.isBlank() ||
        node.name.contains(query, ignoreCase = true) ||
        node.server.contains(query, ignoreCase = true)
    val localNodes = state.nodes.filter { (it.subscriptionId == null || it.subscriptionId !in subscriptionIds) && matches(it) }
    val favorites = state.favoriteIds.toSet()
    val showFavorites = filter == ProfileFilter.FAVORITES.ordinal
    val showLocal = filter == ProfileFilter.ALL.ordinal || filter == ProfileFilter.LOCAL.ordinal
    val showSubscriptions = filter == ProfileFilter.ALL.ordinal || filter == ProfileFilter.SUBSCRIPTIONS.ordinal
    val activeId = if (state.autoSelect) auto?.selectedId else state.selected?.id
    var hidden = 0
    fun arrange(list: List<ProxyNode>) =
        list.arranged(state.serverSort, latency, state.hideUnavailable, setOfNotNull(activeId))
            .also { hidden += list.size - it.size }
    val favoriteNodes = if (showFavorites) arrange(state.nodes.filter { it.id in favorites && matches(it) }) else emptyList()
    val localGroup = if (showLocal) arrange(localNodes) else emptyList()
    val subscriptionGroups = if (showSubscriptions) {
        state.subscriptions.associate { subscription ->
            subscription.id to arrange(state.nodes.filter { it.subscriptionId == subscription.id && matches(it) })
        }
    } else {
        emptyMap()
    }
    // The latency button tests what the current tab shows.
    val visibleNodes = when (ProfileFilter.entries[filter]) {
        ProfileFilter.ALL -> state.nodes
        ProfileFilter.SUBSCRIPTIONS -> state.nodes.filter { it.subscriptionId != null && it.subscriptionId in subscriptionIds }
        ProfileFilter.LOCAL -> state.nodes.filter { it.subscriptionId == null || it.subscriptionId !in subscriptionIds }
        ProfileFilter.FAVORITES -> state.nodes.filter { it.id in favorites }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_profiles)) {
            IconButton(onClick = { viewModel.testNodes(visibleNodes) }, enabled = visibleNodes.isNotEmpty()) {
                Icon(Icons.Default.Speed, stringResource(R.string.action_test))
            }
            Box {
                IconButton(onClick = { sortMenu = true }, enabled = state.nodes.size > 1) {
                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort_title))
                }
                SortMenu(
                    expanded = sortMenu,
                    sort = state.serverSort,
                    hideUnavailable = state.hideUnavailable,
                    onDismiss = { sortMenu = false },
                    onSort = {
                        sortMenu = false
                        viewModel.setServerSort(it)
                    },
                    onHideUnavailable = viewModel::setHideUnavailable,
                )
            }
            IconButton(onClick = { addSheet = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.action_add))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        PillTabs(
            listOf(
                stringResource(R.string.filter_all),
                stringResource(R.string.filter_subscriptions),
                stringResource(R.string.filter_local),
                stringResource(R.string.filter_favorites),
            ),
            filter,
            { filter = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (state.nodes.size > 5) SearchField(query, { query = it })

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            /** Subscription servers are managed by their subscription and can't be deleted one by one. */
            fun LazyListScope.nodes(list: List<ProxyNode>) = items(list, key = { it.id }) { node ->
                val managed = node.subscriptionId != null && node.subscriptionId in subscriptionIds
                NodeCard(
                    modifier = Modifier.animateItem(),
                    node = node,
                    active = !state.autoSelect && node.id == state.selected?.id,
                    autoPick = state.autoSelect && node.id == auto?.selectedId,
                    latency = latency[node.id],
                    favorite = node.id in favorites,
                    onSelect = { viewModel.select(node.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(node.id) },
                    onCopy = { node.link?.let { clipboard.setText(AnnotatedString(it)) } },
                    onShare = { node.link?.let { shareText(context, it, shareTitle) } },
                    onQr = { node.link?.let { qr = Flags.stripFlag(node.name) to it } },
                    onDelete = if (managed) null else ({ pendingDelete = node }),
                )
            }

            if (state.nodes.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_servers),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Auto-select picks from the favourites when there are any, so it fits these two tabs.
            if (state.nodes.size > 1 && (filter == ProfileFilter.ALL.ordinal || showFavorites) && query.isBlank()) {
                item(key = "auto") {
                    val pick = state.current(auto?.selectedId).takeIf { state.autoSelect }
                    AutoCard(
                        active = state.autoSelect,
                        subtitle = when {
                            pick != null -> stringResource(R.string.auto_current, Flags.stripFlag(pick.name))
                            state.autoSelect && auto != null -> stringResource(R.string.auto_measuring)
                            state.autoFromFavorites -> stringResource(R.string.auto_pool_favorites, state.autoNodes.size)
                            else -> stringResource(R.string.auto_pool_all, state.nodes.size)
                        },
                        latency = pick?.let { latency[it.id] },
                        onSelect = viewModel::selectAuto,
                    )
                }
            }
            if (showFavorites) {
                if (favorites.isEmpty() && state.nodes.isNotEmpty()) {
                    item(key = "favorites-empty") {
                        Text(
                            stringResource(R.string.favorites_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                nodes(favoriteNodes)
            }
            if (localGroup.isNotEmpty()) {
                if (showSubscriptions && state.subscriptions.isNotEmpty()) {
                    item(key = "header-local") { GroupTitle(stringResource(R.string.servers_manual)) }
                }
                nodes(localGroup)
            }
            if (showSubscriptions) {
                state.subscriptions.forEach { subscription ->
                    item(key = "header-${subscription.id}") {
                        SubscriptionHeader(
                            subscription = subscription,
                            busy = busy,
                            onRefresh = { viewModel.updateSubscription(subscription) },
                            onCopyUrl = { clipboard.setText(AnnotatedString(subscription.url)) },
                            onQr = { qr = subscription.name to subscription.url },
                            onDelete = { pendingSubscriptionDelete = subscription },
                        )
                    }
                    nodes(subscriptionGroups[subscription.id].orEmpty())
                }
            }
            if (hidden > 0) {
                item(key = "hidden") {
                    Text(
                        stringResource(R.string.hidden_unavailable, hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setHideUnavailable(false) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            }
            item(key = "add") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .clickable { addSheet = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_profile), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.add_profile),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetItem(Icons.Default.ContentPaste, stringResource(R.string.add_clipboard)) {
                    addSheet = false
                    viewModel.import(clipboard.getText()?.text.orEmpty())
                }
                SheetItem(Icons.Default.QrCodeScanner, stringResource(R.string.add_qr)) {
                    addSheet = false
                    onScanQr()
                }
                SheetItem(Icons.Default.Edit, stringResource(R.string.add_manual)) {
                    addSheet = false
                    linkDialog = true
                }
                SheetItem(Icons.Default.RssFeed, stringResource(R.string.add_subscription)) {
                    addSheet = false
                    subscriptionDialog = true
                }
            }
        }
    }

    if (linkDialog) {
        TextInputDialog(
            title = stringResource(R.string.add_manual),
            placeholder = "vless://… ss://… trojan://…",
            confirm = stringResource(R.string.action_import),
            multiline = true,
            onDismiss = { linkDialog = false },
            onConfirm = {
                linkDialog = false
                viewModel.import(it)
            },
        )
    }
    if (subscriptionDialog) {
        SubscriptionDialog(
            onDismiss = { subscriptionDialog = false },
            onAdd = { url, name ->
                subscriptionDialog = false
                viewModel.addSubscription(url, name)
            },
        )
    }
    qr?.let { (title, link) -> QrDialog(title, link, onDismiss = { qr = null }) }
    pendingDelete?.let { node ->
        ConfirmDialog(
            title = stringResource(R.string.delete_title),
            text = node.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.remove(node.id)
                pendingDelete = null
            },
        )
    }
    pendingSubscriptionDelete?.let { subscription ->
        ConfirmDialog(
            title = stringResource(R.string.delete_subscription_title),
            text = subscription.name,
            onDismiss = { pendingSubscriptionDelete = null },
            onConfirm = {
                viewModel.removeSubscription(subscription.id)
                pendingSubscriptionDelete = null
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_servers)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    sort: ServerSort,
    hideUnavailable: Boolean,
    onDismiss: () -> Unit,
    onSort: (ServerSort) -> Unit,
    onHideUnavailable: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            ServerSort.DEFAULT to R.string.sort_default,
            ServerSort.LATENCY to R.string.sort_latency,
            ServerSort.NAME to R.string.sort_name,
        ).forEach { (value, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = { onSort(value) },
                trailingIcon = {
                    if (value == sort) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hide_unavailable)) },
            onClick = { onHideUnavailable(!hideUnavailable) },
            trailingIcon = { Checkbox(checked = hideUnavailable, onCheckedChange = null) },
        )
    }
}

@Composable
fun SheetItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SubscriptionHeader(
    subscription: Subscription,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopyUrl: () -> Unit,
    onQr: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                subscription.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subscriptionSummary(subscription),
                style = MaterialTheme.typography.bodySmall,
                color = if (subscription.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_copy_url)) }, onClick = {
                    menu = false
                    onCopyUrl()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_qr)) }, onClick = {
                    menu = false
                    onQr()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun subscriptionSummary(subscription: Subscription): String {
    subscription.error?.let { return stringResource(R.string.subscription_error, it) }
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    subscription.usage?.let { usage ->
        // total=0 means unlimited traffic, expire=0 means no expiry date.
        parts += if (usage.total > 0) {
            stringResource(R.string.subscription_traffic, Libbox.formatBytes(usage.used), Libbox.formatBytes(usage.total))
        } else {
            stringResource(R.string.subscription_traffic_unlimited, Libbox.formatBytes(usage.used))
        }
        if (usage.expire > 0) {
            val date = DateUtils.formatDateTime(context, usage.expire * 1000, DateUtils.FORMAT_SHOW_DATE)
            parts += stringResource(R.string.subscription_expires, date)
        }
    }
    if (subscription.updatedAt > 0) {
        val ago = DateUtils.getRelativeTimeSpanString(subscription.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        parts += stringResource(R.string.subscription_updated_at, ago)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun AutoCard(active: Boolean, subtitle: String, latency: Latency?, onSelect: () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth(), highlighted = active, onClick = onSelect) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AutoAvatar(40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.auto_select),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (active) ActiveBadge()
                if (active && latency != null) Spacer(Modifier.height(6.dp))
                LatencyText(latency)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(
    node: ProxyNode,
    active: Boolean,
    /** Auto-select's current server. */
    autoPick: Boolean,
    latency: Latency?,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
    /** Null for subscription servers, which are managed by their subscription. */
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    OrbitCard(modifier.fillMaxWidth(), highlighted = active) {
        Box {
            Row(
                Modifier
                    .combinedClickable(onClick = onSelect, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagAvatar(node.name, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Flags.stripFlag(node.name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        protocolLabel(node),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (active) ActiveBadge()
                    if (autoPick) ActiveBadge(stringResource(R.string.auto_select))
                    if ((active || autoPick) && latency != null) Spacer(Modifier.height(6.dp))
                    LatencyText(latency)
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.padding(start = 2.dp).size(36.dp)) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                        tint = if (favorite) FavoriteColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (node.link != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_copy_link)) }, onClick = {
                        menu = false
                        onCopy()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                        menu = false
                        onShare()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_qr)) }, onClick = {
                        menu = false
                        onQr()
                    })
                }
                if (onDelete != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

private val FavoriteColor = Color(0xFFF5B400)

@Composable
private fun ActiveBadge(text: String = stringResource(R.string.badge_active)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
