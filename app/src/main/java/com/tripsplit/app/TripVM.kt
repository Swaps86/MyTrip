package com.tripsplit.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject

sealed class Screen {
    object Home : Screen()
    object Create : Screen()
    data class Detail(val id: String) : Screen()
    data class ExpenseView(val id: String, val eid: String) : Screen()
    data class Form(val id: String, val eid: String?) : Screen()
    data class Toll(val id: String) : Screen()
    data class Share(val id: String) : Screen()
    object Backup : Screen()
    object Join : Screen()
}

class TripVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("trips", 0)
    var screen by mutableStateOf<Screen>(Screen.Home)
    var trips by mutableStateOf(load()); private set
    var customCats by mutableStateOf(loadCats()); private set
    var autoSync by mutableStateOf(prefs.getBoolean("autosync", true)); private set

    /** Finds other phones with the same trip key and merges expenses automatically. */
    val sync = SyncEngine(app, devId(app), { trips }, { tid, r -> applyMerged(tid, r) })

    val allCats: List<String> get() = CATEGORIES + customCats

    var lastBackup by mutableStateOf(prefs.getLong("last_backup", 0L)); private set
    var remind by mutableStateOf(prefs.getBoolean("remind", true)); private set

    fun updateAutoSync(on: Boolean) { autoSync = on; prefs.edit().putBoolean("autosync", on).apply() }

    fun backupText(): String = JSONObject().put("app", "TripSplit").put("format", 1)
        .put("saved", System.currentTimeMillis())
        .put("trips", JSONArray(trips.map { it.toJson() })).put("cats", JSONArray(customCats)).toString()

    fun markBackup() { lastBackup = System.currentTimeMillis(); prefs.edit().putLong("last_backup", lastBackup).apply() }
    fun updateRemind(on: Boolean) { remind = on; prefs.edit().putBoolean("remind", on).apply() }

    private fun setCats(l: List<String>) {
        customCats = l; prefs.edit().putString("cats", JSONArray(l).toString()).apply()
    }

    /** replace = true: use only the backup. false: merge, keeping everything already on the phone. */
    fun restore(b: Backup, replace: Boolean) {
        if (replace) { save(b.trips); setCats(b.cats); return }
        val merged = trips.toMutableList()
        for (bt in b.trips) {
            val i = merged.indexOfFirst { it.id == bt.id }
            if (i < 0) merged.add(bt) else merged[i] = mergeTrips(merged[i], bt)
        }
        save(merged); setCats((customCats + b.cats).distinct())
    }

    private fun load(): List<Trip> = try {
        val a = JSONArray(prefs.getString("data", "[]"))
        List(a.length()) { tripFrom(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun loadCats(): List<String> = try {
        JSONArray(prefs.getString("cats", "[]")).strings()
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<Trip>) {
        trips = l
        prefs.edit().putString("data", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    private fun newKey(name: String): String {
        val abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        fun part() = (1..4).map { abc[r.nextInt(abc.length)] }.joinToString("")
        val prefix = name.filter { it.isLetter() }.take(3).uppercase().padEnd(3, 'X')
        return "$prefix-${part()}-${part()}"
    }

    fun ensureKey(tid: String) {
        val t = trips.find { it.id == tid } ?: return
        if (t.key.isBlank()) save(trips.map { if (it.id == tid) it.copy(key = newKey(it.name)) else it })
    }
    fun resetKey(tid: String) = save(trips.map { if (it.id == tid) it.copy(key = newKey(it.name)) else it })
    fun setMe(tid: String, name: String) = save(trips.map { if (it.id == tid) it.copy(me = name) else it })

    /** Adds a trip received with a key (or links the one already here), keeping every expense. */
    fun joinTrip(t: Trip, me: String) {
        if (trips.any { it.id == t.id }) save(trips.map {
            if (it.id == t.id) mergeTrips(it, t).copy(key = t.key, me = me) else it })
        else save(listOf(t.copy(me = me)) + trips)
    }

    /** Called by auto sync (on the main thread). Returns the expenses that others added. */
    fun applyMerged(tid: String, remote: Trip): List<Expense> {
        val cur = trips.find { it.id == tid } ?: return emptyList()
        val merged = mergeTrips(cur, remote)
        val had = cur.expenses.map { it.id }.toSet()
        val fresh = merged.expenses.filter { it.id !in had && it.addedBy != cur.me }
        if (merged != cur) save(trips.map { if (it.id == tid) merged else it })
        return fresh
    }

    fun addTrip(t: Trip) = save(listOf(t.copy(me = t.me.ifBlank { t.travelers.firstOrNull() ?: "" })) + trips)
    fun deleteTrip(id: String) { save(trips.filter { it.id != id }); screen = Screen.Home }

    fun addTraveler(tid: String, name: String) {
        save(trips.map { if (it.id == tid && name !in it.travelers) it.copy(travelers = it.travelers + name) else it })
        sync.syncNow(tid)
    }

    fun addCategory(name: String) {
        if (name.isBlank() || allCats.any { it.equals(name, true) }) return
        customCats = customCats + name
        prefs.edit().putString("cats", JSONArray(customCats).toString()).apply()
    }

    /** Adds a new expense or replaces the one with the same id, remembering who added or edited it. */
    fun saveExpense(tid: String, e0: Expense) {
        val t = trips.find { it.id == tid } ?: return
        val now = System.currentTimeMillis()
        val me = t.me.ifBlank { t.travelers.firstOrNull() ?: "" }
        val old = t.expenses.find { it.id == e0.id }
        val e = if (old == null) e0.copy(addedBy = e0.addedBy.ifBlank { me }, addedAt = if (e0.addedAt == 0L) now else e0.addedAt, updatedAt = now)
                else e0.copy(addedBy = old.addedBy.ifBlank { me }, addedAt = old.addedAt, updatedAt = now, editedBy = me)
        save(trips.map { x ->
            if (x.id != tid) x else x.copy(expenses = if (old != null) x.expenses.map { if (it.id == e.id) e else it } else listOf(e) + x.expenses)
        })
        sync.syncNow(tid)
    }

    fun deleteExpense(tid: String, eid: String) {
        val now = System.currentTimeMillis()
        save(trips.map { if (it.id == tid) it.copy(expenses = it.expenses.filter { x -> x.id != eid }, deleted = it.deleted + (eid to now)) else it })
        sync.syncNow(tid)
    }
}
