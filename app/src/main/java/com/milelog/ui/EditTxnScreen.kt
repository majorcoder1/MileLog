package com.milelog.ui

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.milelog.data.Fmt
import com.milelog.data.Merchants
import com.milelog.data.TxnType
import com.milelog.export.Exporter
import com.milelog.ui.components.CategorySheet
import com.milelog.ui.components.DatePickerSheet
import com.milelog.ui.components.Divider
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.theme.CardHigh
import com.milelog.ui.theme.Line
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextLow
import com.milelog.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun EditTxnScreen(
    vm: EditTxnVm,
    id: Long,
    type: TxnType,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val txn by vm.txn.collectAsState()
    val categories by vm.categories.collectAsState()
    val purposes by vm.purposes.collectAsState()

    var showPurpose by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var merchantFocused by remember { mutableStateOf(false) }
    val merchantHistory by vm.merchantHistory.collectAsState()
    var pendingPhoto by remember { mutableStateOf<Pair<Uri, File>?>(null) }

    LaunchedEffect(id, type) { vm.load(id, type) }
    // Reseed whenever a different record loads. The old guard was "only if the box is
    // empty", which meant opening a second expense kept the first one's amount on screen
    // — and any edit then saved that number onto the wrong record.
    LaunchedEffect(txn?.id) {
        val cents = txn?.amountCents ?: 0
        amountText = if (cents > 0) String.format(java.util.Locale.US, "%.2f", cents / 100.0) else ""
    }

    val focusManager = LocalFocusManager.current

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingPhoto?.let { (_, file) -> vm.edit { it.copy(receiptPath = file.absolutePath) } }
        else pendingPhoto?.second?.delete()
    }

    val current = txn ?: return
    val isRevenue = current.type == TxnType.REVENUE
    val accent = if (isRevenue) Money else Spend

    // Keep the form above the keyboard so the field being typed into is reachable.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Text(
                if (current.id == 0L) (if (isRevenue) "Add revenue" else "Add expense")
                else (if (isRevenue) "Edit revenue" else "Edit expense"),
                style = MaterialTheme.typography.titleLarge,
                color = TextHi
            )
            if (current.id != 0L) {
                TextButton(onClick = { vm.delete(onClose) }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = Spend)
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }
        Divider()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            // ---- Amount and receipt ----
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { text ->
                        amountText = text.filter { it.isDigit() || it == '.' }
                        val dollars = amountText.toDoubleOrNull() ?: 0.0
                        vm.edit { it.copy(amountCents = Math.round(dollars * 100)) }
                    },
                    prefix = { Text("$", color = accent) },
                    placeholder = { Text("0.00", color = TextLow) },
                    textStyle = MaterialTheme.typography.headlineMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardHigh)
                        .clickable {
                            val dir = File(context.filesDir, "receipts").apply { mkdirs() }
                            val file = File(dir, "receipt-${System.currentTimeMillis()}.jpg")
                            val uri = Exporter.uriFor(context, file)
                            pendingPhoto = uri to file
                            cameraLauncher.launch(uri)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val path = current.receiptPath
                    if (path != null && File(path).exists()) {
                        ReceiptThumb(File(path), Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.PhotoCamera, null, tint = TextMid)
                            Text("Add receipt", style = MaterialTheme.typography.labelSmall, color = TextMid)
                        }
                    }
                }
            }
            Divider()

            FormRow(Icons.Filled.Work, "Choose purpose", purposes.firstOrNull { it.id == current.purposeId }?.name) {
                showPurpose = true
            }
            OutlinedTextField(
                value = current.merchant,
                onValueChange = { v -> vm.edit { it.copy(merchant = v) } },
                label = { Text("Merchant") },
                placeholder = { Text("Who you paid, or who paid you") },
                leadingIcon = { Icon(Icons.Filled.Storefront, null, tint = TextMid) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .onFocusChanged { merchantFocused = it.isFocused }
            )

            // Names he has used before, then the built-in list. Picking one fills in
            // the category it is normally filed under.
            val suggestions = remember(current.merchant, merchantHistory) {
                Merchants.suggest(current.merchant, merchantHistory)
            }
            if (merchantFocused && suggestions.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardHigh)
                ) {
                    suggestions.forEach { name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.pickMerchant(name)
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Storefront, null,
                                tint = TextMid,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, color = TextHi)
                            Merchants.defaultCategoryFor(name)?.let { cat ->
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    cat,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextLow
                                )
                            }
                        }
                    }
                }
            }
            FormRow(Icons.Filled.CalendarMonth, "Date", Fmt.date(current.dateEpochDay)) { showDate = true }
            FormRow(
                Icons.Filled.Folder,
                if (isRevenue) "Revenue category" else "Category",
                categories.firstOrNull { it.id == current.categoryId }?.name
            ) { showCategory = true }

            OutlinedTextField(
                value = current.notes,
                onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                label = { Text("Notes") },
                leadingIcon = { Icon(Icons.Filled.Notes, null, tint = TextMid) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = current.tags,
                onValueChange = { v -> vm.edit { it.copy(tags = v) } },
                label = { Text("Tags, separated by commas") },
                leadingIcon = { Icon(Icons.Filled.LocalOffer, null, tint = TextMid) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(20.dp))
        }

        Divider()
        Button(
            onClick = { vm.save(onClose) },
            enabled = current.amountCents > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
    }

    if (showPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = current.purposeId,
            onPick = { pid -> vm.edit { it.copy(purposeId = pid) }; showPurpose = false },
            onDismiss = { showPurpose = false }
        )
    }
    if (showCategory) {
        CategorySheet(
            categories = categories,
            currentId = current.categoryId,
            onPick = { cid -> vm.edit { it.copy(categoryId = cid) }; showCategory = false },
            onDismiss = { showCategory = false }
        )
    }
    if (showDate) {
        DatePickerSheet(
            initial = LocalDate.ofEpochDay(current.dateEpochDay),
            onPick = { d -> vm.edit { it.copy(dateEpochDay = d.toEpochDay()) } },
            onDismiss = { showDate = false }
        )
    }
}

@Composable
fun FormRow(icon: ImageVector, label: String, value: String?, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = TextMid, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                value ?: label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value == null) TextLow else TextHi,
                modifier = Modifier.weight(1f)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line).padding(start = 20.dp))
    }
}

/**
 * Loads a receipt photo at thumbnail size, the right way up.
 *
 * A phone camera does not rotate the pixels when you turn the phone; it writes the
 * picture as the sensor saw it and records the rotation in an EXIF tag. BitmapFactory
 * ignores that tag, which is why a receipt shot in portrait came back lying on its side.
 * ImageDecoder applies it. Decoding also happens off the main thread, since a camera
 * JPEG is large enough to stutter the screen.
 */
@Composable
fun ReceiptThumb(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    val smallest = minOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(maxOf(1, smallest / 320))
                }
            }.getOrNull()
        }
    }

    val shown = bitmap
    if (shown != null) {
        androidx.compose.foundation.Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = "Receipt photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier.background(CardHigh), contentAlignment = Alignment.Center) {
            Text("Photo missing", style = MaterialTheme.typography.labelSmall, color = TextMid)
        }
    }
}
