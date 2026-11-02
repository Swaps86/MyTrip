package com.tripsplit.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val CATEGORIES = listOf("Food", "Hotel", "Fuel", "Toll", "Shopping", "Tickets")

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val category: String, val amount: Double, val date: Long,
    val note: String, val paidBy: String, val splitAmong: List<String>,
    val location: String = "",
    val ref: String = ""
)

data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val start: Long, val end: Long,
    val destinations: List<String>, val travelers: List<String>,
    val budget: Double, val expenses: List<Expense> = emptyList(),
    val key: String = "", val me: String = ""
) { val spent: Double get() = expenses.sumOf { it.amount } }

data class Payment(val from: String, val to: String, val amount: Double)

/** Net balance per traveler: positive = should receive, negative = owes. */
fun balances(t: Trip): Map<String, Double> {
    val b = t.travelers.associateWith { 0.0 }.toMutableMap()
    for (e in t.expenses) {
        b[e.paidBy] = (b[e.paidBy] ?: 0.0) + e.amount
        if (e.splitAmong.isNotEmpty()) {
            val share = e.amount / e.splitAmong.size
            e.splitAmong.forEach { b[it] = (b[it] ?: 0.0) - share }
        }
    }
    return b
}

/** Minimal set of payments that settles everyone (greedy). */
fun settlements(b: Map<String, Double>): List<Payment> {
    val cr = b.filter { it.value > 0.005 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
    val db = b.filter { it.value < -0.005 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
    val out = mutableListOf<Payment>()
    var i = 0; var j = 0
    while (i < db.size && j < cr.size) {
        val a = minOf(db[i].second, cr[j].second)
        out += Payment(db[i].first, cr[j].first, Math.round(a * 100) / 100.0)
        db[i] = db[i].first to db[i].second - a
        cr[j] = cr[j].first to cr[j].second - a
        if (db[i].second < 0.005) i++
        if (cr[j].second < 0.005) j++
    }
    return out
}

fun JSONArray.strings() = List(length()) { getString(it) }

fun Expense.toJson(): JSONObject = JSONObject().put("id", id).put("cat", category).put("amt", amount)
    .put("date", date).put("note", note).put("paid", paidBy).put("split", JSONArray(splitAmong)).put("loc", location).put("ref", ref)

fun expenseFrom(o: JSONObject) = Expense(o.getString("id"), o.getString("cat"), o.getDouble("amt"),
    o.getLong("date"), o.getString("note"), o.getString("paid"), o.getJSONArray("split").strings(),
    o.optString("loc", ""), o.optString("ref", ""))

fun Trip.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("start", start).put("end", end)
    .put("dest", JSONArray(destinations)).put("trav", JSONArray(travelers)).put("budget", budget)
    .put("exp", JSONArray(expenses.map { it.toJson() })).put("key", key).put("me", me)

fun tripFrom(o: JSONObject) = Trip(o.getString("id"), o.getString("name"), o.getLong("start"), o.getLong("end"),
    o.getJSONArray("dest").strings(), o.getJSONArray("trav").strings(), o.getDouble("budget"),
    o.getJSONArray("exp").let { a -> List(a.length()) { expenseFrom(a.getJSONObject(it)) } },
    key = o.optString("key", ""), me = o.optString("me", ""))
