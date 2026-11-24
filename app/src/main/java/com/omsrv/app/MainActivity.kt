@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.omsrv.app

import android.content.ContentValues
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

data class SurveyPoint(
    val point: String,
    val northing: Double,
    val easting: Double,
    val elevation: Double,
    val code: String = ""
)

class MainActivity : ComponentActivity() {
    private var pointsState by mutableStateOf<List<SurveyPoint>>(samplePoints())
    private var fileNameState by mutableStateOf("Sample project")
    private var messageState by mutableStateOf("Ready / جاهز")
    private var drawingBitmapState by mutableStateOf<Bitmap?>(null)
    private var drawingNameState by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF087E9B),
                secondary = androidx.compose.ui.graphics.Color(0xFF17A6B8)
            )) {
                OMSrvScreen(
                    points = pointsState,
                    fileName = fileNameState,
                    message = messageState,
                    onImport = { openImport() },
                    onExportCsv = { createExport("csv") },
                    onExportTxt = { createExport("txt") },
                    onExportDxf = { createExport("dxf") },
                    onMerge = { mergeSamplePoints() },
                    onLoadDrawing = { drawingLauncher.launch(arrayOf("image/*", "application/pdf", "application/dxf", "application/octet-stream", "*/*")) },
                    drawingBitmap = drawingBitmapState,
                    drawingName = drawingNameState,
                    onOrderChanged = { order ->
                        pointsState = if (order == "E-N-Z") pointsState else pointsState
                        messageState = if (order == "E-N-Z")
                            "Display order: E-N-Z / ترتيب العرض: E-N-Z"
                        else "Display order: N-E-Z / ترتيب العرض: N-E-Z"
                    }
                )
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) readSurveyFile(uri)
    }

    private val drawingLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else "Drawing"
                } ?: "Drawing"
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val bmp = when (ext) {
                    "pdf" -> renderPdfFirstPage(uri)
                    "dxf" -> contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        DxfRasterizer.render(it.readText())
                    }
                    "dwg" -> null
                    else -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) {
                    drawingBitmapState = bmp
                    drawingNameState = name
                    messageState = "Drawing loaded: $name / تم تحميل اللوحة"
                } else {
                    messageState = if (ext == "dwg")
                        "DWG is proprietary: export it to DXF or PDF from CAD first / حوّل DWG إلى DXF أو PDF"
                    else "Unsupported or empty drawing / ملف غير مدعوم"
                }
            } catch (e: Exception) {
                messageState = "Drawing error: ${e.message}"
            }
        }
    }

    private fun renderPdfFirstPage(uri: Uri): Bitmap? {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = minOf(2400f / page.width, 2400f / page.height, 3f)
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) writeExport(uri)
    }

    private var pendingExport: String = "csv"

    private fun openImport() {
        importLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream", "*/*"))
    }

    private fun mergeSamplePoints() {
        val merged = pointsState.associateBy { it.point }.toMutableMap()
        val incoming = samplePoints().map { it.copy(point = "M-${it.point}") }
        incoming.forEach { merged[it.point] = it }
        pointsState = merged.values.sortedBy { it.point }
        messageState = "Merged ${incoming.size} points / تم دمج البيانات"
    }

    private fun readSurveyFile(uri: Uri) {
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "Imported file"
            } ?: "Imported file"
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot read file")
            val parsed = SurveyParser.parseDelimited(content)
            if (parsed.isEmpty()) {
                messageState = "No readable points. Expected: Point,N,E,Z,Code"
            } else {
                pointsState = parsed
                fileNameState = name
                messageState = "Imported ${parsed.size} points / تم استيراد ${parsed.size} نقطة"
            }
        } catch (e: Exception) {
            messageState = "Import error: ${e.message}"
        }
    }

    private fun createExport(format: String) {
        pendingExport = format
        val mime = when (format) {
            "dxf" -> "application/dxf"
            "txt" -> "text/plain"
            else -> "text/csv"
        }
        val ext = if (format == "dxf") "dxf" else format
        exportLauncher.launch("OM_SRV_points.$ext")
    }

    private fun writeExport(uri: Uri) {
        try {
            val body = when (pendingExport) {
                "dxf" -> SurveyExporter.toDxf(pointsState)
                "txt" -> SurveyExporter.toDelimited(pointsState, '\t')
                else -> SurveyExporter.toDelimited(pointsState, ',')
            }
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(body) }
            messageState = "Export completed / تم التصدير بنجاح"
        } catch (e: Exception) {
            messageState = "Export error: ${e.message}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OMSrvScreen(
    points: List<SurveyPoint>,
    fileName: String,
    message: String,
    onImport: () -> Unit,
    onExportCsv: () -> Unit,
    onExportTxt: () -> Unit,
    onExportDxf: () -> Unit,
    onMerge: () -> Unit,
    onLoadDrawing: () -> Unit,
    drawingBitmap: Bitmap?,
    drawingName: String,
    onOrderChanged: (String) -> Unit
) {
    var order by remember { mutableStateOf("N-E-Z") }
    var showPlotter by remember { mutableStateOf(false) }
    var showCadWorkspace by remember { mutableStateOf(false) }
    if (showCadWorkspace && drawingBitmap != null) {
        CadWorkspaceScreen(bitmap = drawingBitmap, drawingName = drawingName, onBack = { showCadWorkspace = false })
        return
    }
    Scaffold(
        containerColor = Color(0xFFF7F4F2),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OM_SRV", fontWeight = FontWeight.Bold)
                        Text("Survey Data Manager", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Image(painter = painterResource(id = R.drawable.om_srv_brand), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.08f)
            Column(Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Image(
                painter = painterResource(id = R.drawable.om_srv_brand),
                contentDescription = "OM_SRV Surveying",
                modifier = Modifier.fillMaxWidth().height(210.dp),
                contentScale = ContentScale.Crop
            )
            DeveloperContactCard()
            Spacer(Modifier.height(10.dp))
            Text("OM_SRV • إدارة بيانات المساحة | Survey Data",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(fileName, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Text("استيراد\nImport")
                }
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
                    Text("CSV")
                }
                OutlinedButton(onClick = onExportDxf, modifier = Modifier.weight(1f)) {
                    Text("DXF")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportTxt, modifier = Modifier.weight(1f)) {
                    Text("TXT")
                }
                OutlinedButton(onClick = onMerge, modifier = Modifier.weight(1f)) {
                    Text("دمج / Merge")
                }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("ترتيب الإحداثيات: $order") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("N-E-Z", "E-N-Z").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = {
                                order = it
                                onOrderChanged(it)
                                expanded = false
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoadDrawing, modifier = Modifier.weight(1f)) { Text("تحميل لوحة CAD / PDF") }
                OutlinedButton(onClick = { showPlotter = !showPlotter }, modifier = Modifier.weight(1f), enabled = drawingBitmap != null) {
                    Text(if (showPlotter) "إخفاء الإسقاط" else "إسقاط النقاط")
                }
            }
            if (drawingBitmap != null) {
                Button(onClick = { showCadWorkspace = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("فتح مساحة CAD للفحص والتكبير")
                }
                Text("اللوحة: $drawingName", style = MaterialTheme.typography.labelSmall)
            }
            if (showPlotter) {
                DrawingPlotter(points = points, bitmap = drawingBitmap, drawingName = drawingName)
            }
            Spacer(Modifier.height(8.dp))
            Text("النقاط: ${points.size} | Points", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        listOf("Point", if (order == "N-E-Z") "Northing" else "Easting",
                            if (order == "N-E-Z") "Easting" else "Northing", "Elevation", "Code")
                            .forEach { HeaderCell(it) }
                    }
                    HorizontalDivider()
                    points.forEach { p ->
                        Row {
                            DataCell(p.point)
                            DataCell(fmt(if (order == "N-E-Z") p.northing else p.easting))
                            DataCell(fmt(if (order == "N-E-Z") p.easting else p.northing))
                            DataCell(fmt(p.elevation))
                            DataCell(p.code)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(12.dp))
            DeveloperContactCard()
            Text(
                "OM_SRV • Survey Data Manager",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
    }
}

@Composable
fun CadWorkspaceScreen(bitmap: Bitmap, drawingName: String, onBack: () -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    androidx.activity.compose.BackHandler { onBack() }
    Scaffold(containerColor = Color.Black, topBar = {
        TopAppBar(title = { Column { Text("عارض اللوحات CAD", color = Color.White); Text(drawingName, color = Color.LightGray, style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Color.White, style = MaterialTheme.typography.headlineMedium) } },
            actions = { TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) { Text("إعادة العرض", color = Color.Cyan) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111923)))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).pointerInput(bitmap) {
                detectTransformGestures { _, pan, factor, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 8f); panX += pan.x; panY += pan.y
                }
            }) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "CAD drawing", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY })
                Text("تكبير بإصبعين وتحريك بإصبعين", modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(12.dp).background(Color(0x99000000)).padding(8.dp), color = Color.White)
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFF111923)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }, modifier = Modifier.weight(1f)) { Text("−", color = Color.White) }
                OutlinedButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(8f) }, modifier = Modifier.weight(1f)) { Text("+", color = Color.White) }
                Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("رجوع") }
            }
        }
    }
}


@Composable
fun DeveloperContactCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("مطور البرنامج", fontWeight = FontWeight.Bold)
            Text("المهندس / عمران سيد", fontWeight = FontWeight.SemiBold)
            Text("استشاري مساحة ونظم المعلومات الجغرافية GIS")
            Text(
                "للتواصل واتساب فقط: 01004156997",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/201004156997"))
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
fun DrawingPlotter(points: List<SurveyPoint>, bitmap: Bitmap?, drawingName: String) {
    var pickedPixels by remember(bitmap) { mutableStateOf(listOf<Offset>()) }
    var status by remember(bitmap) { mutableStateOf("Tap the drawing at the locations of the first 3 control points / اضغط مواقع أول 3 نقاط ضبط") }
    val controls = points.take(3)
    val transform = remember(pickedPixels, controls) {
        if (bitmap != null && controls.size == 3 && pickedPixels.size == 3)
            AffineCalibration.solve(controls, pickedPixels, bitmap.width.toDouble(), bitmap.height.toDouble())
        else null
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("DRAWING & POINT PLOTTER", fontWeight = FontWeight.Bold)
        Text(drawingName.ifBlank { "No drawing loaded / لم يتم تحميل لوحة" },
            style = MaterialTheme.typography.bodySmall)
        if (bitmap == null) {
            Text("Load an image, PDF, or ASCII DXF drawing. DWG must first be converted to DXF/PDF in CAD.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Control points: " + controls.joinToString { it.point } +
                " • ${pickedPixels.size}/3 picked", style = MaterialTheme.typography.bodySmall)
            Canvas(
                Modifier.fillMaxWidth().height(340.dp)
                    .background(Color(0xFFF2F4F6))
                    .pointerInput(bitmap, pickedPixels) {
                        detectTapGestures { tap ->
                            if (pickedPixels.size < 3) {
                                val px = tap.x / size.width * bitmap.width
                                val py = tap.y / size.height * bitmap.height
                                pickedPixels = pickedPixels + Offset(px, py)
                                status = "Control location ${pickedPixels.size} selected"
                            }
                        }
                    }
            ) {
                drawImage(bitmap.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                pickedPixels.forEachIndexed { i, p ->
                    val x = p.x / bitmap.width * size.width
                    val y = p.y / bitmap.height * size.height
                    drawCircle(Color.Red, radius = 9f, center = Offset(x, y))
                    drawCircle(Color.White, radius = 3f, center = Offset(x, y))
                }
                if (transform != null) {
                    points.forEach { point ->
                        val xy = transform.project(point.easting, point.northing)
                        if (xy.first.isFinite() && xy.second.isFinite()) {
                            val x = xy.first / bitmap.width * size.width
                            val y = xy.second / bitmap.height * size.height
                            if (x in 0f..size.width && y in 0f..size.height) {
                                // Survey symbol: ring + crosshair + point number.
                                drawCircle(Color(0xFF00A651), radius = 9f, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f))
                                drawLine(Color(0xFF00A651), Offset(x - 13f, y), Offset(x + 13f, y), strokeWidth = 1.8f)
                                drawLine(Color(0xFF00A651), Offset(x, y - 13f), Offset(x, y + 13f), strokeWidth = 1.8f)
                                drawCircle(Color.White, radius = 2.5f, center = Offset(x, y))
                                drawContext.canvas.nativeCanvas.drawText(
                                    point.point, x + 12f, y - 10f,
                                    android.graphics.Paint().apply {
                                        color = android.graphics.Color.rgb(0, 100, 55)
                                        textSize = 27f
                                        isFakeBoldText = true
                                        setShadowLayer(3f, 1f, 1f, android.graphics.Color.WHITE)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickedPixels = emptyList(); status = "Calibration reset" }) {
                    Text("إعادة الضبط / Reset")
                }
                if (transform != null) {
                    Text("Projected ${points.size} points / تم إسقاط النقاط",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (controls.size < 3) {
                Text("Import at least 3 survey points first / استورد 3 نقاط ضبط على الأقل",
                    color = MaterialTheme.colorScheme.error)
            }
            Text("Important: first 3 points in the table are used as control points. Tap their exact drawing locations in the same order. Symbols show point ID with a crosshair. PDF uses its first page; DXF support is a basic 2D preview of LINE/CIRCLE/ARC/LWPOLYLINE/POINT entities. Verify with independent check points before field use.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}


/**
 * Basic ASCII DXF raster preview. Supports common 2D entities only.
 * Does not preserve CAD layers, lineweights, blocks, text styles or full DXF semantics.
 */
object DxfRasterizer {
    private data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
    private data class Circle(val x: Double, val y: Double, val r: Double)
    fun render(source: String): Bitmap? {
        val lines = source.replace("\r\n", "\n").split('\n')
        if (lines.size < 4) return null
        val pairs = lines.chunked(2).mapNotNull { pair ->
            if (pair.size < 2) null else pair[0].trim().toIntOrNull()?.let { it to pair[1].trim() }
        }
        val segments = mutableListOf<Segment>()
        val circles = mutableListOf<Circle>()
        var i = 0
        while (i < pairs.size) {
            if (pairs[i].first == 0) {
                val type = pairs[i].second.uppercase(Locale.ROOT)
                val end = (i + 1 until pairs.size).firstOrNull { pairs[it].first == 0 } ?: pairs.size
                val entity = pairs.subList(i + 1, end)
                fun value(code: Int) = entity.lastOrNull { it.first == code }?.second?.toDoubleOrNull()
                when (type) {
                    "LINE" -> {
                        val x1=value(10); val y1=value(20); val x2=value(11); val y2=value(21)
                        if (x1!=null && y1!=null && x2!=null && y2!=null) segments.add(Segment(x1,y1,x2,y2))
                    }
                    "CIRCLE" -> {
                        val x=value(10); val y=value(20); val r=value(40)
                        if (x!=null && y!=null && r!=null && r>0) circles.add(Circle(x,y,r))
                    }
                    "POINT" -> {
                        val x=value(10); val y=value(20)
                        if (x!=null && y!=null) segments.add(Segment(x-0.5,y,x+0.5,y))
                    }
                    "LWPOLYLINE" -> {
                        val vertices=mutableListOf<Pair<Double,Double>>()
                        var vx:Double?=null
                        entity.forEach { (code,v) ->
                            if (code==10) { if(vx!=null) { /* previous x awaits y */ }; vx=v.toDoubleOrNull() }
                            if (code==20 && vx!=null) { v.toDoubleOrNull()?.let { vertices.add(vx!! to it) }; vx=null }
                        }
                        for (j in 0 until vertices.size-1) segments.add(Segment(vertices[j].first,vertices[j].second,vertices[j+1].first,vertices[j+1].second))
                        if (entity.any { it.first==70 && ((it.second.toIntOrNull() ?: 0) and 1) == 1 } && vertices.size>2)
                            segments.add(Segment(vertices.last().first,vertices.last().second,vertices.first().first,vertices.first().second))
                    }
                    "ARC" -> {
                        val x=value(10); val y=value(20); val r=value(40)
                        val a1=value(50); val a2=value(51)
                        if(x!=null && y!=null && r!=null && a1!=null && a2!=null) {
                            val sweep=if(a2>=a1) a2-a1 else 360-a1+a2
                            val steps=(sweep/8).toInt().coerceAtLeast(4)
                            var prev:Pair<Double,Double>?=null
                            for(k in 0..steps) {
                                val a=Math.toRadians(a1+sweep*k/steps)
                                val cur=(x+r*kotlin.math.cos(a)) to (y+r*kotlin.math.sin(a))
                                if(prev!=null) segments.add(Segment(prev.first,prev.second,cur.first,cur.second))
                                prev=cur
                            }
                        }
                    }
                }
                i=end
            } else i++
        }
        val allX=segments.flatMap { listOf(it.x1,it.x2) } + circles.flatMap { listOf(it.x-it.r,it.x+it.r) }
        val allY=segments.flatMap { listOf(it.y1,it.y2) } + circles.flatMap { listOf(it.y-it.r,it.y+it.r) }
        if(allX.isEmpty() || allY.isEmpty()) return null
        val minX=allX.minOrNull()!!; val maxX=allX.maxOrNull()!!
        val minY=allY.minOrNull()!!; val maxY=allY.maxOrNull()!!
        val w=1400; val h=1000; val pad=40f
        val spanX=(maxX-minX).coerceAtLeast(1e-6); val spanY=(maxY-minY).coerceAtLeast(1e-6)
        val scale=minOf((w-2*pad)/spanX.toFloat(),(h-2*pad)/spanY.toFloat())
        fun px(x:Double) = pad+(x-minX).toFloat()*scale
        fun py(y:Double) = h-pad-(y-minY).toFloat()*scale
        val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=AndroidCanvas(bmp); canvas.drawColor(android.graphics.Color.WHITE)
        val paint=AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.DKGRAY; style=AndroidPaint.Style.STROKE; strokeWidth=2f
        }
        segments.forEach { canvas.drawLine(px(it.x1),py(it.y1),px(it.x2),py(it.y2),paint) }
        circles.forEach { c -> canvas.drawCircle(px(c.x),py(c.y),c.r.toFloat()*scale,paint) }
        return bmp
    }
}

data class AffineTransform(
    val ax: Double, val bx: Double, val cx: Double,
    val ay: Double, val by: Double, val cy: Double
) {
    fun project(e: Double, n: Double): Pair<Float, Float> =
        Pair((ax * e + bx * n + cx).toFloat(), (ay * e + by * n + cy).toFloat())
}

object AffineCalibration {
    fun solve(points: List<SurveyPoint>, pixels: List<Offset>, width: Double, height: Double): AffineTransform? {
        if (points.size != 3 || pixels.size != 3) return null
        val matrix = Array(3) { i -> doubleArrayOf(points[i].easting, points[i].northing, 1.0) }
        val xs = DoubleArray(3) { pixels[it].x.toDouble() }
        val ys = DoubleArray(3) { pixels[it].y.toDouble() }
        val a = solve3(matrix, xs) ?: return null
        val b = solve3(matrix, ys) ?: return null
        return AffineTransform(a[0], a[1], a[2], b[0], b[1], b[2])
    }

    private fun solve3(input: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) input[r][c] else rhs[r] } }
        for (col in 0..2) {
            var pivot = col
            for (r in col + 1..2) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
            if (kotlin.math.abs(m[pivot][col]) < 1e-12) return null
            val temp = m[col]; m[col] = m[pivot]; m[pivot] = temp
            val div = m[col][col]
            for (c in col..3) m[col][c] /= div
            for (r in 0..2) if (r != col) {
                val factor = m[r][col]
                for (c in col..3) m[r][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(3) { m[it][3] }
    }
}

@Composable
fun HeaderCell(value: String) {
    Text(value, modifier = Modifier.width(112.dp).padding(8.dp), fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium)
}
@Composable
fun DataCell(value: String) {
    Text(value, modifier = Modifier.width(112.dp).padding(8.dp), style = MaterialTheme.typography.bodySmall)
}
fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)

object SurveyParser {
    // Generic delimited reader for Point,N,E,Z[,Code] or a matching named header.
    // SDR/GSI/PNT need vendor-specific adapters and are not guessed by extension.
    fun parseDelimited(raw: String): List<SurveyPoint> {
        val lines = raw.removePrefix("\uFEFF").lineSequence().toList()
        val first = lines.firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") } ?: return emptyList()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> first.count { it == d } }
        val header = split(first, delimiter).map { it.trim().lowercase(Locale.ROOT) }
        val hasHeader = header.any { it.contains("point") || it == "name" || it.contains("northing") || it.contains("easting") }
        val data = if (hasHeader) lines.dropWhile { it != first }.drop(1) else lines
        fun find(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || h.contains(it) } }
        return data.mapNotNull { line ->
            val t=line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val a=split(t,delimiter).map { it.trim().trim('"') }
            try {
                val pi=if(hasHeader)find("point","name","id") else 0
                val ni=if(hasHeader)find("northing","north") else 1
                val ei=if(hasHeader)find("easting","east") else 2
                val zi=if(hasHeader)find("elevation","height","level","z") else 3
                val ci=if(hasHeader)find("code","description","feature") else 4
                if (listOf(pi,ni,ei,zi).any { it<0 || it>=a.size }) return@mapNotNull null
                SurveyPoint(a[pi],a[ni].toDouble(),a[ei].toDouble(),a[zi].toDouble(),
                    if(ci>=0 && ci<a.size) a[ci] else "")
            } catch (_: Exception) { null }
        }
    }

    private fun split(line:String, delimiter:Char):List<String> {
        val result=mutableListOf<String>(); val cell=StringBuilder(); var quoted=false; var i=0
        while(i<line.length) {
            val c=line[i]
            when {
                c=='"' && quoted && i+1<line.length && line[i+1]=='"' -> { cell.append('"'); i++ }
                c=='"' -> quoted=!quoted
                c==delimiter && !quoted -> { result.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        result.add(cell.toString()); return result
    }
}

object SurveyExporter {
    fun toDelimited(points: List<SurveyPoint>, delimiter: Char): String {
        val sep = delimiter.toString()
        return buildString {
            append(listOf("Point", "Northing", "Easting", "Elevation", "Code").joinToString(sep))
            append('\n')
            points.forEach {
                append(listOf(it.point, fmt(it.northing), fmt(it.easting), fmt(it.elevation), it.code).joinToString(sep))
                append('\n')
            }
        }
    }

    // DXF POINT entities: X=Easting, Y=Northing, Z=Elevation.
    fun toDxf(points: List<SurveyPoint>): String = buildString {
        append("0\nSECTION\n2\nENTITIES\n")
        points.forEach { p ->
            append("0\nPOINT\n8\n${safeLayer(p.code)}\n")
            append("10\n${p.easting}\n20\n${p.northing}\n30\n${p.elevation}\n")
        }
        append("0\nENDSEC\n0\nEOF\n")
    }
    private fun safeLayer(code: String) = code.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "POINTS" }
}

fun samplePoints() = listOf(
    SurveyPoint("1001", 3351200.125, 320450.500, 18.250, "P"),
    SurveyPoint("1002", 3351210.300, 320460.750, 18.675, "COL"),
    SurveyPoint("1003", 3351220.550, 320470.900, 19.020, "BM")
)
