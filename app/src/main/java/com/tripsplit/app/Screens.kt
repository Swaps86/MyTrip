package com.tripsplit.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val APP_VERSION = "1.0.0"
private val Bg = Color(0xFFF6F7F4)
private val Green = Color(0xFF0F6B5C)
private val Muted = Color(0xFF55605A)
private val Owe = Color(0xFFA33A1A)
private val Get = Color(0xFF0B6B3A)

fun inr(d: Double): String {
    val f = NumberFormat.getNumberInstance(Locale("en", "IN"))
    f.maximumFractionDigits = 2; f.minimumFractionDigits = 0
    return (if (d < 0) "-₹" else "₹") + f.format(Math.abs(d))
}

fun fmtDate(ms: Long): String {
    val f = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH); f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms))
}

fun todayUtc(): Long {
    val c = Calendar.getInstance()
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

@Composable
private fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }

@Composable
private fun Progress(spent: Double, budget: Double) {
    val p = if (budget > 0) (spent / budget).coerceIn(0.0, 1.0).toFloat() else 0f
    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp),
        color = if (budget > 0 && spent > budget) Owe else Green)
}

@Composable
private fun Header(title: String, sub: String? = null, onBack: () -> Unit, action: @Composable () -> Unit = {}) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
        }
        action()
    }

@Composable
fun Footer() = Column(Modifier.fillMaxWidth().background(Bg).padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Shri Swami Samarth", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green)
    Text("© 2026 Swapnil Palande · Version $APP_VERSION", fontSize = 12.sp, color = Muted)
}

// ---------- 1. Trip list ----------
@Composable
fun HomeScreen(vm: TripVM) {
    var about by remember { mutableStateOf(false) }
    var homeMenu by remember { mutableStateOf(false) }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About TripSplit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Trip expense tracker")
                Text("Developed by Swapnil Palande")
                Text("Version $APP_VERSION")
                Text("© 2026 Swapnil Palande. All rights reserved.", color = Muted, fontSize = 13.sp)
                Text("Shri Swami Samarth", color = Green, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } })
    Scaffold(containerColor = Bg, bottomBar = { Footer() }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { vm.screen = Screen.Create },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("New trip") })
    }) { p ->
        Column(Modifier.padding(p).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("My trips", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Box {
                    IconButton(onClick = { homeMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                        DropdownMenuItem(text = { Text("Backup and restore") }, onClick = { homeMenu = false; vm.screen = Screen.Backup })
                        DropdownMenuItem(text = { Text("Join a trip") }, onClick = { homeMenu = false; vm.screen = Screen.Join })
                        DropdownMenuItem(text = { Text("About") }, onClick = { homeMenu = false; about = true })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val due = vm.remind && vm.trips.isNotEmpty() &&
                (vm.lastBackup == 0L || System.currentTimeMillis() - vm.lastBackup > 7 * 86_400_000L)
            if (due) {
                WhiteCard(Modifier.clickable { vm.screen = Screen.Backup }) {
                    Text("Back up your trips", fontWeight = FontWeight.Bold)
                    Text(if (vm.lastBackup == 0L) "You have not made a backup yet."
                         else "Last backup was ${(System.currentTimeMillis() - vm.lastBackup) / 86_400_000L} days ago.",
                        color = Muted, fontSize = 13.sp)
                    Text("Tap to back up now", color = Green, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (vm.trips.isEmpty()) Text("No trips yet. Tap “New trip” to start.", color = Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.trips, key = { it.id }) { t ->
                    WhiteCard(Modifier.clickable { vm.screen = Screen.Detail(t.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(fmtDate(t.start), color = Muted, fontSize = 13.sp)
                        }
                        Text("${t.destinations.joinToString().ifBlank { "No destinations" }} · ${t.travelers.size} travelers",
                            color = Muted, fontSize = 13.sp)
                        Progress(t.spent, t.budget)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Spent ${inr(t.spent)}", color = Muted, fontSize = 13.sp)
                            Text("Budget ${inr(t.budget)}", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 2. Create trip ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("$label: ${fmtDate(value)}") }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = value)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { st.selectedDateMillis?.let(onChange); open = false }) { Text("OK") }
        }) { DatePicker(st) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipEditor(label: String, items: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun add() { val t = text.trim(); if (t.isNotEmpty() && t !in items) onChange(items + t); text = "" }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = { IconButton(onClick = { add() }) { Icon(Icons.Default.Add, "Add") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { name ->
            AssistChip(onClick = { onChange(items - name) }, label = { Text(name) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove $name", Modifier.size(16.dp)) })
        }
    }
}

@Composable
fun CreateScreen(vm: TripVM) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableLongStateOf(todayUtc()) }
    var end by remember { mutableLongStateOf(todayUtc()) }
    var dests by remember { mutableStateOf(listOf<String>()) }
    var trav by remember { mutableStateOf(listOf<String>()) }
    var budget by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && trav.isNotEmpty() && end >= start
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("New trip", onBack = { vm.screen = Screen.Home })
            OutlinedTextField(name, { name = it }, label = { Text("Trip name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            DateField("Start", start, { start = it; if (end < it) end = it }, Modifier.fillMaxWidth())
            DateField("End", end, { end = it }, Modifier.fillMaxWidth())
            if (end < start) Text("End date must be after start date", color = Owe, fontSize = 13.sp)
            ChipEditor("Add destination", dests) { dests = it }
            ChipEditor("Add traveler (name)", trav) { trav = it }
            OutlinedTextField(budget, { budget = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Total budget (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(enabled = ok, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                vm.addTrip(Trip(name = name.trim(), start = start, end = end, destinations = dests,
                    travelers = trav, budget = budget.toDoubleOrNull() ?: 0.0))
                vm.screen = Screen.Home
            }) { Text("Create trip") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------- 3. Trip dashboard + report ----------
@Composable
fun DetailScreen(trip: Trip, vm: TripVM) {
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addPerson by remember { mutableStateOf(false) }
    var view by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val left = trip.budget - trip.spent
    val groups = trip.expenses.groupBy { it.paidBy }.toList().sortedByDescending { g -> g.second.sumOf { it.amount } }
    val byCat = trip.expenses.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        .toList().sortedByDescending { it.second }
    var newTolls by remember { mutableStateOf<List<TollSms>>(emptyList()) }
    LaunchedEffect(trip.id, trip.expenses.size) {
        if (hasSmsPermission(ctx)) {
            val r = withContext(Dispatchers.IO) { scanTollSms(ctx, trip) }
            newTolls = r.inTrip.filter { t -> trip.expenses.none { it.ref == t.ref } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete “${trip.name}”?") }, text = { Text("All its expenses will be removed.") },
        confirmButton = { TextButton(onClick = { vm.deleteTrip(trip.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    if (addPerson) {
        var nm by remember { mutableStateOf("") }
        val clean = nm.trim()
        AlertDialog(onDismissRequest = { addPerson = false }, title = { Text("Add person to trip") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nm, { nm = it }, label = { Text("Name") }, singleLine = true)
                    if (clean in trip.travelers) Text("This name is already in the trip", color = Owe, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = clean.isNotEmpty() && clean !in trip.travelers,
                    onClick = { vm.addTraveler(trip.id, clean); addPerson = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addPerson = false }) { Text("Cancel") } })
    }

    Scaffold(containerColor = Bg, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { vm.screen = Screen.Form(trip.id, null) },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add expense") })
    }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Header(trip.name, "${fmtDate(trip.start)} – ${fmtDate(trip.end)}", { vm.screen = Screen.Home }) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Import tolls and UPI from SMS") }, onClick = { menu = false; vm.screen = Screen.Toll(trip.id) })
                            DropdownMenuItem(text = { Text("Delete trip") }, onClick = { menu = false; confirm = true })
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Green)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Spent so far", color = Color.White, fontSize = 13.sp)
                        Text(inr(trip.spent), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        val p2 = if (trip.budget > 0) (trip.spent / trip.budget).coerceIn(0.0, 1.0).toFloat() else 0f
                        LinearProgressIndicator(progress = { p2 }, Modifier.fillMaxWidth().height(8.dp),
                            color = Color.White, trackColor = Color(0x664F9A8C))
                        Text(if (left >= 0) "${inr(left)} left of ${inr(trip.budget)}"
                             else "Over budget by ${inr(-left)} (budget ${inr(trip.budget)})",
                            color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            item {
                WhiteCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${trip.travelers.size} travelers", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { addPerson = true }) { Text("+ Add person") }
                    }
                }
            }
            item {
                WhiteCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Walkie-talkie", fontWeight = FontWeight.Bold)
                            Text("Talk to your group on Wi-Fi", color = Muted, fontSize = 13.sp)
                        }
                        Button(onClick = { vm.ensureKey(trip.id); vm.screen = Screen.Walkie(trip.id) }) { Text("Open") }
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.ensureKey(trip.id); vm.screen = Screen.Invite(trip.id) }) { Text("Invite people") }
                }
            }
            item {
                TabRow(selectedTabIndex = tab, containerColor = Bg) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Expenses") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Report") })
                }
            }
            if (tab == 0) {
                if (newTolls.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4F1))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${newTolls.size} toll / UPI payment message${if (newTolls.size > 1) "s" else ""} found",
                                fontWeight = FontWeight.Bold, color = Color(0xFF0B4F44))
                            Text("Dated within this trip · total ${inr(newTolls.sumOf { it.amount })}", color = Muted, fontSize = 13.sp)
                            Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.screen = Screen.Toll(trip.id) }) {
                                Text("Review and add")
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View:", color = Muted, fontSize = 13.sp)
                        FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text("By date") })
                        FilterChip(selected = view == 1, onClick = { view = 1 }, label = { Text("By person") })
                    }
                }
                if (view == 0 && byCat.isNotEmpty()) item {
                    WhiteCard {
                        byCat.forEach { (c, a) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c, fontWeight = FontWeight.SemiBold)
                                Text("${inr(a)} · ${(a / trip.spent * 100).toInt()}%", color = Muted)
                            }
                        }
                    }
                }
                if (trip.expenses.isEmpty()) item { Text("No expenses yet. Tap “Add expense”.", color = Muted) }
                else item { Text("Tap an expense to edit or delete it", color = Muted, fontSize = 13.sp) }
                items(if (view == 0) trip.expenses else emptyList(), key = { it.id }) { e ->
                    WhiteCard(Modifier.clickable { vm.screen = Screen.ExpenseView(trip.id, e.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.category + refTag(e.ref), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("${e.paidBy} paid · ${fmtDate(e.date)}", color = Muted, fontSize = 13.sp)
                                if (e.location.isNotBlank()) Text(e.location, color = Muted, fontSize = 13.sp)
                                if (e.note.isNotBlank()) Text(e.note, color = Muted, fontSize = 13.sp)
                            }
                            Text(inr(e.amount), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("  ›", color = Muted, fontSize = 20.sp)
                        }
                    }
                }
                if (view == 1) {
                    groups.forEach { (name, list) ->
                        val total = list.sumOf { it.amount }
                        item(key = "g_$name") {
                            WhiteCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Text("${list.size} payment${if (list.size > 1) "s" else ""}", color = Muted, fontSize = 13.sp)
                                    }
                                    Text(inr(total), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                }
                                list.sortedBy { it.date }.forEach { e ->
                                    Row(Modifier.fillMaxWidth().clickable { vm.screen = Screen.ExpenseView(trip.id, e.id) }
                                        .padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${e.category} · ${fmtDate(e.date)}"); Text(inr(e.amount))
                                    }
                                }
                                if (list.size > 1) Text(list.joinToString(" + ") { inr(it.amount) } + " = " + inr(total),
                                    color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                item {
                    WhiteCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Total spent", color = Muted, fontSize = 13.sp)
                                Text(inr(trip.spent), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Budget ${inr(trip.budget)}", color = Muted, fontSize = 13.sp)
                                val pct = if (trip.budget > 0) (trip.spent / trip.budget * 100).toInt() else 0
                                Text("$pct% used", fontWeight = FontWeight.Bold)
                            }
                        }
                        Progress(trip.spent, trip.budget)
                        Text(if (left >= 0) "Remaining: ${inr(left)}" else "Over budget by ${inr(-left)}",
                            color = Muted, fontSize = 13.sp)
                    }
                }
                if (byCat.isNotEmpty()) {
                    item { Text("BY CATEGORY", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    item {
                        WhiteCard {
                            byCat.forEach { (c, a) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(c); Text(inr(a), fontWeight = FontWeight.Bold)
                                }
                                Progress(a, trip.spent)
                            }
                        }
                    }
                    item { Text("PAID BY EACH PERSON", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    item {
                        WhiteCard {
                            groups.forEach { (name, list) ->
                                val tot = list.sumOf { it.amount }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(name, fontWeight = FontWeight.SemiBold)
                                        Text("${list.map { it.category }.distinct().joinToString(" + ")} · ${list.size} payment${if (list.size > 1) "s" else ""}",
                                            color = Muted, fontSize = 13.sp)
                                    }
                                    Text(inr(tot), fontWeight = FontWeight.Bold)
                                }
                                Progress(tot, trip.spent)
                            }
                            Text("A person's payments are added together", color = Muted, fontSize = 13.sp)
                        }
                    }
                    item { Text("EXPENSES (${trip.expenses.size})", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    item {
                        WhiteCard {
                            trip.expenses.sortedBy { it.date }.take(4).forEach { e ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(e.category, fontWeight = FontWeight.SemiBold)
                                        Text(fmtDate(e.date), color = Muted, fontSize = 13.sp)
                                    }
                                    Text(inr(e.amount), fontWeight = FontWeight.Bold)
                                }
                            }
                            if (trip.expenses.size > 4)
                                Text("+ ${trip.expenses.size - 4} more in the PDF", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Button(enabled = trip.expenses.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = { downloadPdf(ctx, trip) }) { Text("Download PDF") }
                }
                item {
                    OutlinedButton(enabled = trip.expenses.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = { shareText(ctx, summaryText(trip)) }) { Text("Share summary on WhatsApp") }
                }
                item {
                    OutlinedButton(enabled = trip.expenses.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = { sharePdf(ctx, trip) }) { Text("Share PDF on WhatsApp") }
                }
            }
        }
    }
}

// ---------- 4. Expense details (Edit / Delete) ----------
@Composable
fun ExpenseScreen(trip: Trip, e: Expense, vm: TripVM) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete this expense?") }, text = { Text("${e.category} · ${inr(e.amount)}") },
        confirmButton = {
            TextButton(onClick = { vm.deleteExpense(trip.id, e.id); vm.screen = Screen.Detail(trip.id) }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    val n = e.splitAmong.size.coerceAtLeast(1)
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Expense", trip.name, onBack = { vm.screen = Screen.Detail(trip.id) })
            WhiteCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(e.category + refTag(e.ref), color = Green, fontWeight = FontWeight.SemiBold)
                    Text(inr(e.amount), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(fmtDate(e.date), color = Muted)
                }
            }
            WhiteCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Paid by", color = Muted); Text(e.paidBy, fontWeight = FontWeight.SemiBold)
                }
            }
            if (e.location.isNotBlank()) WhiteCard {
                Text("LOCATION", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(e.location)
            }
            if (e.note.isNotBlank()) WhiteCard {
                Text("NOTES", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(e.note)
            }
            Spacer(Modifier.height(8.dp))
            Button(modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { vm.screen = Screen.Form(trip.id, e.id) }) { Text("Edit") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = { confirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Owe)) { Text("Delete expense") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------- 5. Add / Edit expense ----------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseForm(trip: Trip, existing: Expense?, vm: TripVM) {
    val ctx = LocalContext.current
    var amount by remember {
        mutableStateOf(existing?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    var cat by remember { mutableStateOf(existing?.category ?: vm.allCats.first()) }
    var date by remember { mutableLongStateOf(existing?.date ?: todayUtc()) }
    var paid by remember { mutableStateOf(existing?.paidBy ?: trip.travelers.first()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var detecting by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    val amt = amount.toDoubleOrNull() ?: 0.0
    val cats = (vm.allCats + cat).distinct()

    fun detect(force: Boolean) {
        detecting = true
        detectPlace(ctx) { r ->
            detecting = false
            if (r != null && (force || location.isBlank())) location = r
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) detect(false)
    }
    fun ensureAndDetect(force: Boolean) {
        if (hasLocPermission(ctx)) detect(force)
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (existing == null) ensureAndDetect(false) }

    if (newCat) {
        var nm by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newCat = false }, title = { Text("New category") },
            text = { OutlinedTextField(nm, { nm = it }, label = { Text("Category name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = nm.isNotBlank(), onClick = {
                    val c = nm.trim(); vm.addCategory(c)
                    cat = vm.allCats.firstOrNull { it.equals(c, true) } ?: c
                    newCat = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newCat = false }) { Text("Cancel") } })
    }
    val back = { vm.screen = if (existing != null) Screen.ExpenseView(trip.id, existing.id) else Screen.Detail(trip.id) }
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(if (existing != null) "Edit expense" else "Add expense", trip.name, onBack = { back() })
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Category", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) }) }
                AssistChip(onClick = { newCat = true }, label = { Text("+ Add") })
            }
            DateField("Date", date, { date = it }, Modifier.fillMaxWidth())
            Text("Paid by", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.travelers.forEach { n -> FilterChip(selected = paid == n, onClick = { paid = n }, label = { Text(n) }) }
            }
            OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(if (detecting) "Detecting your location…" else "Auto-detected · you can edit it") },
                trailingIcon = {
                    IconButton(onClick = { ensureAndDetect(true) }) { Icon(Icons.Default.Refresh, "Detect location again") }
                })
            OutlinedTextField(note, { note = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = amt > 0, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val e = (existing ?: Expense(category = cat, amount = amt, date = date, note = "", paidBy = paid,
                    splitAmong = trip.travelers)).copy(category = cat, amount = amt, date = date,
                    note = note.trim(), paidBy = paid, location = location.trim())
                vm.saveExpense(trip.id, e)
                vm.screen = if (existing != null) Screen.ExpenseView(trip.id, e.id) else Screen.Detail(trip.id)
            }) { Text(if (existing != null) "Save changes" else "Save expense") }
            if (existing != null) OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { back() }) { Text("Cancel") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------- 6. Toll and UPI payments from SMS ----------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TollScreen(trip: Trip, vm: TripVM) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasSmsPermission(ctx)) }
    var paste by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(listOf<TollSms>()) }
    var scan by remember { mutableStateOf(ScanResult(emptyList(), 0)) }
    var scanned by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(trip.travelers.first()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var cats by remember { mutableStateOf(mapOf<String, String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) {
            scan = withContext(Dispatchers.IO) { scanTollSms(ctx, trip) }
            scanned = true
            picked = picked + scan.inTrip.filter { t -> t.confident && trip.expenses.none { it.ref == t.ref } }.map { it.ref }
        }
    }
    val all = (scan.inTrip + extra).distinctBy { it.ref }
    fun isNew(t: TollSms) = trip.expenses.none { it.ref == t.ref }
    fun catOf(t: TollSms) = cats[t.ref] ?: t.category
    val chosen = all.filter { it.ref in picked && isNew(it) }

    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Toll and UPI from SMS", trip.name, onBack = { vm.screen = Screen.Detail(trip.id) })
            if (!granted && !skipped && extra.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Read payment messages?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                WhiteCard {
                    Text("1. Only FASTag toll messages and UPI “Sent Rs…” payment messages are checked.")
                    Text("2. Only messages dated within this trip, ${fmtDate(trip.start)} – ${fmtDate(trip.end)}.")
                    Text("3. Everything stays on your phone. Nothing is uploaded.")
                    Text("4. You choose which payments to add and their category.")
                }
                Button(modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow and continue") }
                OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = { skipped = true; paste = true }) { Text("Not now, paste a message instead") }
            } else {
                if (granted && scanned) WhiteCard {
                    Text("${scan.inTrip.size} payment messages found", fontWeight = FontWeight.Bold)
                    Text("Dated within your trip (${fmtDate(trip.start)} – ${fmtDate(trip.end)})", color = Muted, fontSize = 13.sp)
                }
                if (!granted) OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS access to find payments automatically") }
                if (granted && scanned && all.isEmpty()) Text("No toll or UPI payment messages found within the trip dates.", color = Muted)
                all.forEach { t ->
                    key(t.ref) {
                        val fresh = isNew(t)
                        var open by remember { mutableStateOf(false) }
                        WhiteCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = t.ref in picked && fresh, enabled = fresh,
                                    onCheckedChange = { on -> picked = if (on) picked + t.ref else picked - t.ref })
                                Column(Modifier.weight(1f)) {
                                    Text(t.plaza, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(listOf(fmtDate(t.dateUtc), t.time).filter { it.isNotBlank() }.joinToString(" · "),
                                        color = Muted, fontSize = 13.sp)
                                    val sub = if (t.kind == "upi") t.detail else listOfNotNull(
                                        if (t.vrn.isNotBlank()) "VRN ${t.vrn}" else null,
                                        t.balance?.let { "FASTag balance ${inr(it)}" }).joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 13.sp)
                                    if (!fresh) Text("Already added", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(inr(t.amount), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            if (fresh && t.kind == "upi") Box {
                                AssistChip(onClick = { open = true },
                                    label = { Text("Category: ${catOf(t)}${if (!t.confident && t.ref !in cats) " (check)" else ""}") })
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    vm.allCats.forEach { c ->
                                        DropdownMenuItem(text = { Text(c) }, onClick = { cats = cats + (t.ref to c); open = false })
                                    }
                                }
                            }
                        }
                    }
                }
                if (chosen.isNotEmpty()) Text("Selected ${chosen.size} · Total ${inr(chosen.sumOf { it.amount })}", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { paste = !paste }) { Text("Paste a message manually") }
                if (paste) {
                    OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste toll or UPI SMS here") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedButton(enabled = pasted.isNotBlank(), onClick = {
                        val r = parseTolls(pasted) + parseUpi(pasted)
                        if (r.isEmpty()) Toast.makeText(ctx, "No toll or UPI message found in this text", Toast.LENGTH_LONG).show()
                        else { extra = extra + r; picked = picked + r.filter { it.confident }.map { it.ref }; pasted = ""; paste = false }
                    }) { Text("Read message") }
                }
                Text("Who paid?", color = Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trip.travelers.forEach { n -> FilterChip(selected = paid == n, onClick = { paid = n }, label = { Text(n) }) }
                }
                Button(enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    chosen.forEach { t ->
                        vm.saveExpense(trip.id, Expense(category = catOf(t), amount = t.amount, date = t.dateUtc,
                            note = tollNote(t), paidBy = paid, splitAmong = trip.travelers,
                            location = if (t.kind == "upi") t.plaza else "${t.plaza} toll plaza", ref = t.ref))
                    }
                    Toast.makeText(ctx, "${chosen.size} expense${if (chosen.size > 1) "s" else ""} added", Toast.LENGTH_SHORT).show()
                    vm.screen = Screen.Detail(trip.id)
                }) { Text("Add ${chosen.size} expense${if (chosen.size == 1) "" else "s"}") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------- 7. Backup and restore ----------
@Composable
fun BackupScreen(vm: TripVM) {
    val ctx = LocalContext.current
    var savedName by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Backup?>(null) }
    var replace by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val b = readTextFromUri(ctx, uri)?.let { parseBackup(it) }
            if (b == null) Toast.makeText(ctx, "This is not a TripSplit backup file", Toast.LENGTH_LONG).show()
            else { pending = b; replace = false }
        }
    }
    val expenseCount = vm.trips.sumOf { it.expenses.size }
    pending?.let { b ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${b.trips.size} trips · ${b.trips.sumOf { it.expenses.size }} expenses" +
                        if (b.saved > 0) " · saved ${fmtDate(b.saved)}" else "", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().clickable { replace = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !replace, onClick = { replace = false })
                        Column { Text("Merge with current data", fontWeight = FontWeight.SemiBold)
                            Text("Adds missing trips and expenses. Nothing is deleted.", color = Muted, fontSize = 13.sp) }
                    }
                    Row(Modifier.fillMaxWidth().clickable { replace = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = replace, onClick = { replace = true })
                        Column { Text("Replace everything", fontWeight = FontWeight.SemiBold)
                            Text("Deletes current data and uses the backup only.", color = Muted, fontSize = 13.sp) }
                    }
                    Text("A safety copy of your current data is saved to Downloads first.", color = Muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.trips.isNotEmpty()) saveTextToDownloads(ctx, backupFileName("TripSplit_SafetyCopy"), vm.backupText())
                    vm.restore(b, replace)
                    Toast.makeText(ctx, "Backup restored", Toast.LENGTH_SHORT).show()
                    pending = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Backup and restore", onBack = { vm.screen = Screen.Home })
            WhiteCard {
                Text("Your data on this phone", fontWeight = FontWeight.Bold)
                Text("${vm.trips.size} trips · $expenseCount expenses · ${vm.customCats.size} own categories", color = Muted, fontSize = 13.sp)
                Text("Last backup: " + if (vm.lastBackup == 0L) "Never"
                    else SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date(vm.lastBackup)),
                    color = Muted, fontSize = 13.sp)
            }
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val name = backupFileName()
                if (saveTextToDownloads(ctx, name, vm.backupText())) { vm.markBackup(); savedName = name }
                else Toast.makeText(ctx, "Could not save the backup", Toast.LENGTH_LONG).show()
            }) { Text("Back up now") }
            savedName?.let { n ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4F1))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Backup saved", fontWeight = FontWeight.Bold, color = Color(0xFF0B4F44))
                        Text("Downloads / $n", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { shareBackupFile(ctx, savedName ?: backupFileName(), vm.backupText()) }) {
                Text("Share backup (WhatsApp, Drive…)")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { openDownloads(ctx) }) { Text("Open Downloads folder") }
            Text("The file holds all trips, expenses and categories. Keep a copy somewhere safe.", color = Muted, fontSize = 13.sp)
            Text("RESTORE", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { picker.launch(arrayOf("*/*")) }) { Text("Restore from backup file") }
            Text("Use this on a new phone or after reinstalling the app.", color = Muted, fontSize = 13.sp)
            WhiteCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backup reminder", fontWeight = FontWeight.Bold)
                        Text("Shows a note on the home screen when the last backup is over 7 days old.", color = Muted, fontSize = 13.sp)
                    }
                    Switch(checked = vm.remind, onCheckedChange = { vm.updateRemind(it) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------- 8. Invite, join and walkie-talkie ----------
@Composable
fun InviteScreen(trip: Trip, vm: TripVM) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Reset trip key?") },
        text = { Text("People who already joined will no longer connect. Send the new invite only to the people you want.") },
        confirmButton = { TextButton(onClick = { vm.resetKey(trip.id); confirm = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Invite people", trip.name, onBack = { vm.screen = Screen.Detail(trip.id) })
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4F1))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TRIP KEY", color = Muted, fontSize = 13.sp)
                    Text(trip.key.ifBlank { "..." }, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B4F44))
                    Text("Only phones with this key can join", color = Muted, fontSize = 13.sp)
                }
            }
            WhiteCard {
                Text("People in this trip", fontWeight = FontWeight.Bold)
                Text(trip.travelers.joinToString(" · "), color = Muted, fontSize = 13.sp)
            }
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), enabled = trip.key.isNotBlank(),
                onClick = { shareToWhatsApp(ctx, makeInviteText(trip)) }) { Text("Send invite on WhatsApp") }
            OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp), enabled = trip.key.isNotBlank(), onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TripSplit invite", makeInviteText(trip)))
                Toast.makeText(ctx, "Invite copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy invite") }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5))) {
                Column(Modifier.padding(14.dp)) {
                    Text("Send only to your group", fontWeight = FontWeight.Bold)
                    Text("Anyone who gets the invite can join. Do not post it in a public group.", color = Muted, fontSize = 13.sp)
                }
            }
            TextButton(onClick = { confirm = true }) { Text("Reset trip key", color = Owe) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun JoinScreen(vm: TripVM) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val invite = remember(text) { parseInvite(text) }
    var who by remember(invite) { mutableStateOf<String?>(null) }
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Join a trip", onBack = { vm.screen = Screen.Home })
            if (invite == null) {
                Text("Paste the invite message you received on WhatsApp.", color = Muted)
                OutlinedTextField(text, { text = it }, label = { Text("Invite message") },
                    modifier = Modifier.fillMaxWidth(), minLines = 4)
                OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                }) { Text("Paste from clipboard") }
                if (text.isNotBlank()) Text("No TripSplit invite found in this text.", color = Owe, fontSize = 13.sp)
            } else {
                WhiteCard {
                    Text(invite.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${fmtDate(invite.start)} – ${fmtDate(invite.end)}", color = Muted, fontSize = 13.sp)
                    if (invite.destinations.isNotEmpty()) Text(invite.destinations.joinToString(", "), color = Muted, fontSize = 13.sp)
                }
                Text("WHO ARE YOU?", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                invite.travelers.forEach { n ->
                    WhiteCard(Modifier.clickable { who = n }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = who == n, onClick = { who = n })
                            Text(n, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Pick your own name.", color = Muted, fontSize = 13.sp)
                Button(enabled = who != null, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    vm.joinTrip(invite, who!!)
                    Toast.makeText(ctx, "Joined ${invite.name}", Toast.LENGTH_SHORT).show()
                    vm.screen = Screen.Detail(invite.id)
                }) { Text(if (who != null) "Join as $who" else "Join trip") }
                OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = { text = "" }) { Text("Not now") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun WalkieScreen(trip: Trip, vm: TripVM) {
    val ctx = LocalContext.current
    var micOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    Scaffold(containerColor = Bg) { p ->
        Column(Modifier.padding(p).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header("Walkie-talkie", trip.name, onBack = { vm.screen = Screen.Detail(trip.id) })
            if (trip.key.isBlank()) {
                Text("Open “Invite people” first to create the trip key.", color = Muted)
            } else if (trip.me.isBlank()) {
                Text("Who are you on this phone?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                trip.travelers.forEach { n ->
                    WhiteCard(Modifier.clickable { vm.setMe(trip.id, n) }) { Text(n, fontWeight = FontWeight.SemiBold) }
                }
            } else if (!micOk) {
                Text("The walkie-talkie needs the microphone.", color = Muted)
                Button(modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
            } else {
                WalkieBody(trip)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WalkieBody(trip: Trip) {
    val appCtx = LocalContext.current.applicationContext
    val view = LocalView.current
    val engine = remember(trip.id, trip.key, trip.me) { WalkieEngine(appCtx, trip.id, trip.key, trip.me) }
    DisposableEffect(engine) { engine.start(); onDispose { engine.stop() } }
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    fun online(n: String) = n == trip.me || (engine.seen[n] ?: 0L) > now - 6000
    val onlineCount = trip.travelers.count { online(it) }

    WhiteCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${trip.name} · Wi-Fi", fontWeight = FontWeight.Bold)
                Text(engine.error ?: "$onlineCount of ${trip.travelers.size} online · you are ${trip.me}", color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = { engine.muted = !engine.muted }) { Text(if (engine.muted) "Unmute" else "Mute") }
        }
    }
    val talker = engine.talkingName
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (engine.iAmTalking) "You are talking…" else if (talker != null) "$talker is talking…" else "Nobody is talking",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (engine.iAmTalking) Owe else Color.Unspecified)
        Text(if (engine.iAmTalking) "Release to stop" else "Hold the big button to talk", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(190.dp).clip(CircleShape).background(if (engine.iAmTalking) Owe else Green)
            .pointerInput(engine) {
                detectTapGestures(onPress = { engine.talk(true); try { awaitRelease() } finally { engine.talk(false) } })
            }, contentAlignment = Alignment.Center) {
            Text(if (engine.iAmTalking) "Release" else "Hold to talk", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
    WhiteCard {
        trip.travelers.forEach { n ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(n + if (n == trip.me) " (You)" else "", fontWeight = FontWeight.SemiBold)
                    Text(if (talker == n) "Talking…" else if (online(n)) "Online" else "Offline", color = Muted, fontSize = 13.sp)
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (online(n)) Color(0xFF1B9E5A) else Color(0xFFB7BFBA)))
            }
        }
    }
    Text("Everyone must be on the same Wi-Fi or phone hotspot, with TripSplit open. Only people who joined this trip can hear you.",
        color = Muted, fontSize = 13.sp)
}
