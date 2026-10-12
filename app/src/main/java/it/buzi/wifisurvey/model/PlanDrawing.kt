package it.buzi.wifisurvey.model

import org.json.JSONArray
import org.json.JSONObject

/** A room: axis-aligned rectangle in metres. */
class Room(val id: String = newId(), var name: String, var x: Double, var y: Double, var w: Double, var h: Double) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("x", x).put("y", y).put("w", w).put("h", h)
    companion object {
        fun fromJson(o: JSONObject) = Room(o.optString("id", newId()), o.optString("name", ""), o.getDouble("x"), o.getDouble("y"), o.getDouble("w"), o.getDouble("h"))
    }
}

/** A free wall segment in metres. */
class Wall(val id: String = newId(), var x1: Double, var y1: Double, var x2: Double, var y2: Double) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
    companion object {
        fun fromJson(o: JSONObject) = Wall(o.optString("id", newId()), o.getDouble("x1"), o.getDouble("y1"), o.getDouble("x2"), o.getDouble("y2"))
    }
}

/** A door: centre point in metres, width, and orientation (true = opening along a horizontal wall). */
class Door(val id: String = newId(), var x: Double, var y: Double, var width: Double = 1.0, var horizontal: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("x", x).put("y", y).put("width", width).put("horizontal", horizontal)
    companion object {
        fun fromJson(o: JSONObject) = Door(o.optString("id", newId()), o.getDouble("x"), o.getDouble("y"), o.optDouble("width", 1.0), o.optBoolean("horizontal", true))
    }
}

/** Vector floor plan drawn in the app. Units are metres; origin top-left. */
class PlanDrawing(
    var widthM: Double = 60.0,
    var heightM: Double = 40.0,
    val rooms: MutableList<Room> = mutableListOf(),
    val walls: MutableList<Wall> = mutableListOf(),
    val doors: MutableList<Door> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val r = JSONArray(); rooms.forEach { r.put(it.toJson()) }
        val w = JSONArray(); walls.forEach { w.put(it.toJson()) }
        val d = JSONArray(); doors.forEach { d.put(it.toJson()) }
        return JSONObject().put("widthM", widthM).put("heightM", heightM).put("rooms", r).put("walls", w).put("doors", d)
    }

    fun copy(): PlanDrawing = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): PlanDrawing {
            val p = PlanDrawing(o.optDouble("widthM", 60.0), o.optDouble("heightM", 40.0))
            val r = o.optJSONArray("rooms") ?: JSONArray()
            for (i in 0 until r.length()) p.rooms.add(Room.fromJson(r.getJSONObject(i)))
            val w = o.optJSONArray("walls") ?: JSONArray()
            for (i in 0 until w.length()) p.walls.add(Wall.fromJson(w.getJSONObject(i)))
            val d = o.optJSONArray("doors") ?: JSONArray()
            for (i in 0 until d.length()) p.doors.add(Door.fromJson(d.getJSONObject(i)))
            return p
        }

        /** Pixels per metre used when the drawing is rasterised (fixed, so survey coordinates stay valid). */
        fun pxPerMeter(widthM: Double, heightM: Double): Double {
            val base = 40.0
            val maxSide = maxOf(widthM, heightM) * base
            return if (maxSide > 4000) 4000.0 / maxOf(widthM, heightM) else base
        }
    }
}
