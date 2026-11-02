package com.tripsplit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF0F6B5C), background = Color(0xFFF6F7F4), surface = Color(0xFFF6F7F4))) {
                App()
            }
        }
    }
}

@Composable
fun App(vm: TripVM = viewModel()) {
    val s = vm.screen
    BackHandler(s != Screen.Home) {
        vm.screen = when (s) {
            is Screen.Form -> if (s.eid != null) Screen.ExpenseView(s.id, s.eid) else Screen.Detail(s.id)
            is Screen.ExpenseView -> Screen.Detail(s.id)
            is Screen.Toll -> Screen.Detail(s.id)
            is Screen.Invite -> Screen.Detail(s.id)
            is Screen.Walkie -> Screen.Detail(s.id)
            is Screen.Chat -> Screen.Detail(s.id)
            else -> Screen.Home
        }
    }
    when (s) {
        Screen.Home -> HomeScreen(vm)
        Screen.Create -> CreateScreen(vm)
        Screen.Backup -> BackupScreen(vm)
        Screen.Join -> JoinScreen(vm)
        is Screen.Invite -> vm.trips.find { it.id == s.id }?.let { InviteScreen(it, vm) } ?: HomeScreen(vm)
        is Screen.Walkie -> vm.trips.find { it.id == s.id }?.let { HubScreen(it, vm, 1) } ?: HomeScreen(vm)
        is Screen.Chat -> vm.trips.find { it.id == s.id }?.let { HubScreen(it, vm, 0) } ?: HomeScreen(vm)
        is Screen.Detail -> vm.trips.find { it.id == s.id }?.let { DetailScreen(it, vm) } ?: HomeScreen(vm)
        is Screen.ExpenseView -> {
            val t = vm.trips.find { it.id == s.id }
            val e = t?.expenses?.find { it.id == s.eid }
            if (t != null && e != null) ExpenseScreen(t, e, vm) else HomeScreen(vm)
        }
        is Screen.Toll -> vm.trips.find { it.id == s.id }?.let { TollScreen(it, vm) } ?: HomeScreen(vm)
        is Screen.Form -> {
            val t = vm.trips.find { it.id == s.id }
            if (t != null) ExpenseForm(t, t.expenses.find { it.id == s.eid }, vm) else HomeScreen(vm)
        }
    }
}
