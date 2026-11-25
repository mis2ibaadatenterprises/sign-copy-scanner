package com.ibaadat.invoicescanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Base64

private val Bg      = Color(0xFF0F1419)
private val Surface = Color(0xFF171D25)
private val Line    = Color(0xFF2B3440)
private val Ink     = Color(0xFFE8EDF2)
private val Muted   = Color(0xFF8B98A7)
private val Accent  = Color(0xFFF0A830)
private val Ok      = Color(0xFF3FB950)
private val Warn    = Color(0xFFE3734A)

private enum class Screen { HOME, READING, CONFIRM, QUEUE, SETTINGS }

class MainActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF1A1206),
                    background = Bg, surface = Surface, onSurface = Ink
                )
            ) { App() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { Prefs(ctx) }

        var screen by remember { mutableStateOf(Screen.HOME) }
        var hasCam by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED)
        }
        var shot by remember { mutableStateOf<Bitmap?>(null) }
        var parsed by remember { mutableStateOf<Parsed?>(null) }
        var invoice by remember { mutableStateOf("") }
        var party by remember { mutableStateOf("") }
        var date by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var queueN by remember { mutableIntStateOf(0) }
        var update by remember { mutableStateOf<Updater.Available?>(null) }
        val snack = remember { SnackbarHostState() }

        val askCam = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { hasCam = it }

        LaunchedEffect(Unit) {
            if (!hasCam) askCam.launch(Manifest.permission.CAMERA)
            queueN = Queue.count(ctx)
            if (queueN > 0) Queue.kick(ctx)
            withContext(Dispatchers.IO) { update = Updater.check(ctx, prefs) }
        }

        fun say(msg: String) = scope.launch { snack.showSnackbar(msg) }

        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface, titleContentColor = Ink),
                    title = { Text("Invoice Scanner", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (queueN > 0) AssistChip(
                            onClick = { screen = Screen.QUEUE },
                            label = { Text("$queueN pending", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        IconButton({ queueN = Queue.count(ctx); screen = Screen.QUEUE }) {
                            Icon(Icons.Default.Menu, "Queue", tint = Muted)
                        }
                        IconButton({ screen = Screen.SETTINGS }) {
                            Icon(Icons.Default.Settings, "Settings", tint = Muted)
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                update?.let { u ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Version ${u.versionName} is available", color = Ink, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            TextButton({
                                scope.launch(Dispatchers.IO) { runCatching { Updater.install(ctx, u.apkUrl) } }
                            }) { Text("Update now", color = Accent) }
                        }
                    }
                }

                when (screen) {

                    Screen.HOME -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.padding(12.dp)) {
                                if (hasCam) CameraBox() else
                                    Text("Camera permission is off. Tap below to allow it.",
                                        color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Lay the copy flat. Fill the frame. Keep the top edge straight.",
                                    color = Muted, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Big("Capture invoice", enabled = hasCam && !busy) {
                            busy = true
                            capture(
                                onDone = { bmp ->
                                    scope.launch {
                                        screen = Screen.READING
                                        val clean = withContext(Dispatchers.Default) { Scan.clean(bmp) }
                                        shot = clean
                                        val text = runCatching { Scan.read(clean) }.getOrDefault("")
                                        val p = Fields.parse(text)
                                        parsed = p
                                        invoice = p.invoice.value; party = p.party.value; date = p.date.value
                                        busy = false
                                        screen = Screen.CONFIRM
                                        if (!p.invoice.found || !p.party.found) say("Some fields need typing in")
                                    }
                                },
                                onFail = { busy = false; say("Capture failed — try again") }
                            )
                        }
                        if (!hasCam) {
                            Spacer(Modifier.height(10.dp))
                            Ghost("Allow camera") { askCam.launch(Manifest.permission.CAMERA) }
                        }
                    }

                    Screen.READING -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.padding(14.dp)) {
                                shot?.let {
                                    androidx.compose.foundation.Image(
                                        it.asImageBitmap(), null,
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                                Spacer(Modifier.height(10.dp))
                                Text("Reading the invoice…", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }

                    Screen.CONFIRM -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Head("Check before upload")
                                Field("Invoice number", invoice, parsed?.invoice) { invoice = it }
                                Field("Party name", party, parsed?.party) { party = it }
                                Field("Invoice date", date, parsed?.date) { date = it }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    Fields.fileName(invoice, party, date),
                                    color = Accent, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Big(if (busy) "Uploading…" else "Upload to Drive", enabled = !busy) {
                            if (!prefs.configured) { say("Set the login and godown first"); screen = Screen.SETTINGS }
                            else {
                                busy = true
                                scope.launch {
                                    val bmp = shot!!
                                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }
                                        .format(java.util.Date())
                                    val job = withContext(Dispatchers.Default) {
                                        JSONObject()
                                            .put("filename", Fields.fileName(invoice, party, date))
                                            .put("invoice", invoice.trim())
                                            .put("party", party.trim())
                                            .put("date", date.trim())
                                            .put("date_folder", today)
                                            .put("pdf", Base64.encodeToString(Scan.toPdf(bmp), Base64.NO_WRAP))
                                    }
                                    val result = withContext(Dispatchers.IO) { Net.upload(prefs, job) }
                                    busy = false
                                    when (result) {
                                        is Net.Outcome.Ok -> say("On the board — " + invoice.trim())
                                        is Net.Outcome.Rejected -> say("Rejected: ${result.reason}")
                                        is Net.Outcome.Offline -> {
                                            withContext(Dispatchers.IO) { Queue.add(ctx, job) }
                                            Queue.kick(ctx); queueN = Queue.count(ctx)
                                            say("No signal — queued, will upload itself")
                                        }
                                    }
                                    shot = null; parsed = null; invoice = ""; party = ""; date = ""
                                    screen = Screen.HOME
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Ghost("Retake") { shot = null; screen = Screen.HOME }
                    }

                    Screen.QUEUE -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Head("Pending uploads")
                                val jobs = remember(queueN) { Queue.pending(ctx) }
                                if (jobs.isEmpty())
                                    Text("Nothing pending. Every scan is on Drive.",
                                        color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                                else jobs.forEach { f ->
                                    val j = runCatching { JSONObject(f.readText()) }.getOrNull()
                                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text(j?.optString("invoice").orEmpty().ifBlank { "—" },
                                                color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text(j?.optString("party").orEmpty().ifBlank { "—" },
                                                color = Muted, fontSize = 12.sp)
                                        }
                                        Text("waiting", color = Muted, fontSize = 12.sp)
                                    }
                                    HorizontalDivider(color = Line)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Big("Retry uploads now") { Queue.kick(ctx); queueN = Queue.count(ctx); say("Retrying…") }
                        Spacer(Modifier.height(10.dp))
                        Ghost("Back to camera") { screen = Screen.HOME }
                    }

                    Screen.SETTINGS -> {
                        var url by remember { mutableStateOf(prefs.endpoint) }
                        var lid by remember { mutableStateOf(prefs.loginId) }
                        var pwd by remember { mutableStateOf(prefs.password) }
                        var upd by remember { mutableStateOf(prefs.updateUrl) }
                        var gds by remember { mutableStateOf(prefs.godowns) }
                        var pick by remember { mutableStateOf(prefs.godown) }

                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Head("Board login")
                                Box2("Web app URL", url) { url = it }
                                Box2("Login ID", lid) { lid = it }
                                Box2("Password", pwd) { pwd = it }
                                Box2("Update check URL (optional)", upd) { upd = it }
                                Text("The same ID used on the godown board. Sign copies are filed under this name.",
                                    color = Muted, fontSize = 12.sp)
                            }
                        }

                        if (gds.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
                                Column(Modifier.padding(16.dp)) {
                                    Head("Godown for this phone")
                                    gds.forEach { (k, label) ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = pick == k,
                                                onClick = { pick = k; prefs.godown = k },
                                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(label, color = Ink, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Big(if (busy) "Signing in…" else "Save & sign in", enabled = !busy) {
                            prefs.endpoint = url; prefs.loginId = lid
                            prefs.password = pwd; prefs.updateUrl = upd
                            prefs.token = ""
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Net.login(prefs) }
                                busy = false
                                gds = prefs.godowns; pick = prefs.godown
                                say(when (r) {
                                    is Net.Outcome.Ok -> if (gds.size > 1 && pick.isBlank())
                                        "Signed in — now pick the godown" else "Signed in as ${prefs.name}"
                                    is Net.Outcome.Rejected -> r.reason
                                    is Net.Outcome.Offline -> "Could not reach the board"
                                })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Ghost("Back to camera") { screen = Screen.HOME }
                    }
                }
            }
        }
    }

    /* ---------- camera ---------- */

    @Composable
    private fun CameraBox() {
        val ctx = LocalContext.current
        val owner = LocalLifecycleOwner.current
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(10.dp)),
            factory = { c ->
                val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(view.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    imageCapture = capture
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }
                }, ContextCompat.getMainExecutor(c))
                view
            }
        )
    }

    private fun capture(onDone: (Bitmap) -> Unit, onFail: () -> Unit) {
        val capture = imageCapture ?: return onFail()
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = image.toBitmap()
                    val deg = image.imageInfo.rotationDegrees
                    image.close()
                    onDone(if (deg == 0) bmp else Bitmap.createBitmap(
                        bmp, 0, 0, bmp.width, bmp.height,
                        Matrix().apply { postRotate(deg.toFloat()) }, true
                    ))
                }
                override fun onError(exc: ImageCaptureException) = onFail()
            }
        )
    }
}

/* ---------- small UI pieces ---------- */

@Composable private fun Head(t: String) {
    Text(t.uppercase(), color = Muted, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable private fun Big(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1206))
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable private fun Ghost(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable private fun Box2(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Line,
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedLabelColor = Accent, unfocusedLabelColor = Muted
        )
    )
}

/** Text field carrying the parser's verdict, so staff know where to look. */
@Composable private fun Field(label: String, value: String, f: com.ibaadat.invoicescanner.Field?, onChange: (String) -> Unit) {
    val badge = when {
        f == null || !f.found -> "not found" to Warn
        f.confidence >= 0.8f  -> "read clearly" to Ok
        else                  -> "check this" to Accent
    }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 0.6.sp)
            Spacer(Modifier.width(8.dp))
            Text(badge.first, color = badge.second, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Line,
                focusedTextColor = Ink, unfocusedTextColor = Ink
            )
        )
    }
}
