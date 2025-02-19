package org.sunsetware.phocid

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.ibm.icu.util.ULocale
import java.lang.ref.WeakReference
import java.net.URLConnection
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.apache.commons.io.FilenameUtils
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.data.getArtworkColor
import org.sunsetware.phocid.data.preferencesSystemLocale
import org.sunsetware.phocid.ui.components.AnimatedForwardBackwardTransition
import org.sunsetware.phocid.ui.components.DragLock
import org.sunsetware.phocid.ui.theme.PhocidTheme
import org.sunsetware.phocid.ui.views.PermissionRequestDialog
import org.sunsetware.phocid.ui.views.library.LibraryScreen
import org.sunsetware.phocid.ui.views.player.PlayerScreen
import org.sunsetware.phocid.utils.combine

class MainActivity : ComponentActivity(), IntentLauncher {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val dismissSplashScreen = AtomicBoolean(false)

        installSplashScreen().setKeepOnScreenCondition { !dismissSplashScreen.get() }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        // Set locale to the actual locale displayed, or the user might see funny formatting
        preferencesSystemLocale = requireNotNull(LocaleListCompat.getDefault()[0])
        val resourceLocaleTag = Strings[R.string.locale]
        val resourceLocale = Locale.forLanguageTag(resourceLocaleTag)
        val systemLocale = LocaleListCompat.getDefault().getFirstMatch(arrayOf(resourceLocaleTag))
        val locale =
            if (systemLocale?.language == resourceLocale.language) systemLocale else resourceLocale
        ULocale.setDefault(ULocale.forLocale(locale))

        super.onCreate(savedInstanceState)

        setContent {
            val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            val coroutineScope = rememberCoroutineScope()
            val viewModel = viewModel<MainViewModel>()
            val initialized by viewModel.initialized.collectAsStateWithLifecycle()
            var permissionGranted by remember { mutableStateOf(false) }
            val permissions =
                rememberMultiplePermissionsState(
                    listOfNotNull(READ_PERMISSION),
                    onPermissionsResult = { result -> permissionGranted = result.all { it.value } },
                )
            permissionGranted = permissions.permissions.all { it.status.isGranted }

            val uiManager = viewModel.uiManager
            val topLevelScreenStack by uiManager.topLevelScreenStack.collectAsStateWithLifecycle()
            val dialog by uiManager.dialog.collectAsStateWithLifecycle()
            val backHandlerEnabled by uiManager.backHandlerEnabled.collectAsStateWithLifecycle()
            // Don't put locks in ViewModel, as they should be reset after activity recreation.
            val playerScreenOpenDragLock = remember { DragLock() }
            val playerScreenCloseDragLock = remember { DragLock() }
            val playerScreenDragState = uiManager.playerScreenDragState
            playerScreenDragState.coroutineScope = WeakReference(coroutineScope)
            val overrideStatusBarLightColor by
                uiManager.overrideStatusBarLightColor.collectAsStateWithLifecycle()

            viewModel.uiManager.intentLauncher = WeakReference(this)

            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            val currentTrackColor by
                remember {
                        viewModel.playerManager.state.combine(
                            coroutineScope,
                            viewModel.libraryIndex,
                            viewModel.preferences,
                        ) { state, library, preferences ->
                            if (state.actualPlayQueue.isEmpty()) null
                            else
                                library.tracks[state.actualPlayQueue[state.currentIndex]]
                                    ?.getArtworkColor(preferences.artworkColorPreference)
                        }
                    }
                    .collectAsStateWithLifecycle()

            LaunchedEffect(lifecycleState) {
                if (lifecycleState == Lifecycle.State.RESUMED) {
                    viewModel.initialize { dismissSplashScreen.set(true) }
                    viewModel.scanLibrary(false)
                }
            }
            LaunchedEffect(permissionGranted) {
                if (!permissionGranted) {
                    uiManager.openDialog(PermissionRequestDialog(permissions))
                } else {
                    uiManager.closeDialog()
                }
            }
            LaunchedEffect(permissionGranted) {
                if (permissionGranted) viewModel.scanLibrary(false)
            }

            BackHandler(backHandlerEnabled) { uiManager.back() }

            PhocidTheme(
                themeColorSource = preferences.themeColorSource,
                customThemeColor = preferences.customThemeColor,
                overrideThemeColor =
                    if (preferences.coloredGlobalTheme) currentTrackColor else null,
                darkTheme = preferences.darkTheme.boolean ?: isSystemInDarkTheme(),
                pureBackgroundColor = preferences.pureBackgroundColor,
                overrideStatusBarLightColor = overrideStatusBarLightColor,
                densityMultiplier = preferences.densityMultiplier,
            ) {
                Box(
                    modifier =
                        Modifier.background(MaterialTheme.colorScheme.surface).onSizeChanged {
                            playerScreenDragState.length = it.height.toFloat()
                        }
                ) {
                    if (initialized) {
                        AnimatedForwardBackwardTransition(topLevelScreenStack) { screen ->
                            when (screen) {
                                null -> {
                                    LibraryScreen(
                                        playerScreenOpenDragLock,
                                        isObscured =
                                            playerScreenDragState.position == 1f ||
                                                topLevelScreenStack.isNotEmpty(),
                                    )

                                    if (playerScreenDragState.position > 0) {
                                        val scrimColor = MaterialTheme.colorScheme.scrim
                                        Box(
                                            modifier =
                                                Modifier.fillMaxSize().drawBehind {
                                                    drawRect(
                                                        scrimColor,
                                                        alpha = playerScreenDragState.position,
                                                    )
                                                }
                                        )
                                        Box(
                                            modifier =
                                                Modifier.offset {
                                                    IntOffset(
                                                        0,
                                                        ((1 - playerScreenDragState.position) *
                                                                playerScreenDragState.length)
                                                            .roundToInt(),
                                                    )
                                                }
                                        ) {
                                            PlayerScreen(playerScreenCloseDragLock)
                                        }
                                    }
                                }
                                else -> screen.Compose(viewModel)
                            }
                        }

                        if (dialog != null) {
                            dialog!!.Compose(viewModel)
                        }
                    }
                }
            }
        }
    }

    private val openDocumentTreeContinuation = AtomicReference(null as ((Uri?) -> Unit)?)
    private val openDocumentTreeIntent =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            openDocumentTreeContinuation.get()?.invoke(uri)
        }

    override fun openDocumentTree(continuation: (Uri?) -> Unit) {
        openDocumentTreeContinuation.set(continuation)
        openDocumentTreeIntent.launch(null)
    }

    override fun share(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val shareIntent =
            if (tracks.size == 1) {
                Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, tracks.first().uri)
                    // guessContentTypeFromName can fail if file name contains special characters
                    type =
                        URLConnection.guessContentTypeFromName(
                            "a." + FilenameUtils.getExtension(tracks.first().path)
                        )
                }
            } else {
                Intent().apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(tracks.map { it.uri }),
                    )
                    type =
                        tracks
                            .map {
                                URLConnection.guessContentTypeFromName(
                                    "a." + FilenameUtils.getExtension(it.path)
                                )
                            }
                            .distinct()
                            .singleOrNull() ?: "audio/*"
                }
            }
        startActivity(Intent.createChooser(shareIntent, null))
    }
}
