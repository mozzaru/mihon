package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        var showCustomUrlDialog by remember { mutableStateOf(false) }
        var customUrl by remember { mutableStateOf("") }
        var currentUrl by remember { mutableStateOf("") } // State untuk menyimpan URL yang ditambahkan
        
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
                
        val openLabel = stringResource(MR.strings.custom_link_open)
        val cancelLabel = stringResource(MR.strings.action_cancel)
        val urlLabel = stringResource(MR.strings.custom_link_label)
        val urlPlaceholder = stringResource(MR.strings.custom_link_placeholder)
        val invalidUrlMsg = stringResource(MR.strings.invalid_url)

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            if (state.toolbarQuery != null) {
                screenModel.setToolbarQuery(null)
            } else {
                navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
        } else {
            val scope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            val uriHandler = LocalUriHandler.current
            val snackbarHostState = remember { SnackbarHostState() }

            val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
            val onWebViewClick = {
                val source = screenModel.source as? HttpSource ?: return
                // Gunakan currentUrl jika ada, jika tidak gunakan baseUrl
                val urlToOpen = if (currentUrl.isNotEmpty()) currentUrl else source.baseUrl
                navigator.push(
                    WebViewScreen(
                        url = urlToOpen,
                        initialTitle = source.name,
                        sourceId = source.id,
                    ),
                )
            }

            LaunchedEffect(screenModel.source) {
                assistUrl = (screenModel.source as? HttpSource)?.baseUrl
            }

            Scaffold(
                topBar = {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = screenModel::search,
                        onCustomLinkClick = { showCustomUrlDialog = true }, // Tampilkan dialog
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },            
            ) { paddingValues ->
                BrowseSourceContent(
                    source = screenModel.source,
                    mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                    columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                    displayMode = screenModel.displayMode,
                    snackbarHostState = snackbarHostState,
                    contentPadding = paddingValues,
                    onWebViewClick = onWebViewClick,
                    onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                    onLocalSourceHelpClick = onHelpClick,
                    onMangaClick = { navigator.push((MangaScreen(it.id, true))) },
                    onMangaLongClick = { manga ->
                        scope.launchIO {
                            val duplicates = screenModel.getDuplicateLibraryManga(manga)
                            when {
                                manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                                duplicates.isNotEmpty() -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                                )
                                else -> screenModel.addFavorite(manga)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                )
            }
            
            if (showCustomUrlDialog) {
                AlertDialog(
                    onDismissRequest = { showCustomUrlDialog = false },
                    title = { Text(stringResource(MR.strings.action_custom_link)) },
                    text = {
                        TextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text(urlLabel) },
                            singleLine = true,
                            placeholder = { Text(urlPlaceholder) },
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (customUrl.startsWith("http://") || customUrl.startsWith("https://")) {
                                    currentUrl = customUrl // Simpan URL yang ditambahkan
                                    showCustomUrlDialog = false
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(invalidUrlMsg)
                                    }
                                }
                            }
                        ) {
                            Text(openLabel)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomUrlDialog = false }) {
                            Text(cancelLabel)
                        }
                    }
                )
            }

            val onDismissRequest = { screenModel.setDialog(null) }
            when (val dialog = state.dialog) {
                is BrowseSourceScreenModel.Dialog.Filter -> {
                    SourceFilterDialog(
                        onDismissRequest = onDismissRequest,
                        filters = state.filters,
                        onReset = screenModel::resetFilters,
                        onFilter = { screenModel.search(filters = state.filters) },
                        onUpdate = screenModel::setFilters,
                    )
                }
                is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                    DuplicateMangaDialog(
                        duplicates = dialog.duplicates,
                        onDismissRequest = onDismissRequest,
                        onConfirm = { screenModel.addFavorite(dialog.manga) },
                        onOpenManga = { navigator.push(MangaScreen(it.id)) },
                        onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                    )
                }

                is BrowseSourceScreenModel.Dialog.Migrate -> {
                    MigrateMangaDialog(
                        current = dialog.current,
                        target = dialog.target,
                        onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                        onDismissRequest = onDismissRequest,
                    )
                }
                is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                    RemoveMangaDialog(
                        onDismissRequest = onDismissRequest,
                        onConfirm = {
                            screenModel.changeMangaFavorite(dialog.manga)
                        },
                        mangaToRemove = dialog.manga,
                    )
                }
                is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                    ChangeCategoryDialog(
                        initialSelection = dialog.initialSelection,
                        onDismissRequest = onDismissRequest,
                        onEditCategories = { navigator.push(CategoryScreen()) },
                        onConfirm = { include, _ ->
                            screenModel.changeMangaFavorite(dialog.manga)
                            screenModel.moveMangaToCategories(dialog.manga, include)
                        },
                    )
                }
                else -> {}
            }

            LaunchedEffect(Unit) {
                queryEvent.receiveAsFlow()
                    .collectLatest {
                        when (it) {
                            is SearchType.Genre -> screenModel.searchGenre(it.txt)
                            is SearchType.Text -> screenModel.search(it.txt)
                        }
                    }
            }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
