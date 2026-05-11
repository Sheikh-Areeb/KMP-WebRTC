package com.webrtckmp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.webrtckmp.models.CallState
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // We don't need to react to the result here — the user can simply
            // tap "Join Room" again once they've granted permission.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 6+ requires runtime requests for "dangerous" permissions.
        // The CAMERA + RECORD_AUDIO manifest entries only grant the *ability*
        // to ask — without this call, the camera open fails with SecurityException
        // and the WebRTC capturer silently produces no frames.
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        val viewModel = CallViewModel(context = PlatformContext())

        setContent {
            App(viewModel = viewModel) { callState ->
                VideoLayer(viewModel = viewModel)
            }
        }
    }
}

/**
 * Hosts the remote (full-screen) and local (PiP) SurfaceViewRenderers.
 *
 * Key challenge: the renderers are created by Compose's factory lambdas, but
 * the WebRTCManager needs references to them. We use remember + LaunchedEffect
 * to pass them once both surfaces exist.
 */
@Composable
private fun VideoLayer(viewModel: CallViewModel) {
    val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Once we have both renderers, register them with the manager.
    // LaunchedEffect re-runs whenever the keys change — here they only flip
    // once (null → renderer), so this block runs exactly one time.
    LaunchedEffect(localRenderer.value, remoteRenderer.value) {
        val local = localRenderer.value
        val remote = remoteRenderer.value
        if (local != null && remote != null) {
            viewModel.attachRenderers(local, remote)
        }
    }

    // SurfaceViewRenderer.init() can only be called ONCE per renderer instance —
    // calling it again throws "Already initialized".  Doing it inside the
    // AndroidView factory guarantees it runs exactly once per renderer, no
    // matter how often Compose recomposes or attachRenderers() is invoked.
    val eglContext = WebRTCManager.eglBase.eglBaseContext

    // Remote video — full-screen background
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                remoteRenderer.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    // Local video — picture-in-picture overlay
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglContext, null)
                    setMirror(true)
                    localRenderer.value = this
                }
            },
            modifier = Modifier
                .size(width = 120.dp, height = 160.dp)
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
