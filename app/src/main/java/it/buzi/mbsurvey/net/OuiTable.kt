package it.buzi.mbsurvey.net

import android.content.Context

/** IEEE OUI → vendor, loaded lazily from assets/oui.txt (40k entries, ~1.2 MB). */
object OuiTable {
    @Volatile private var table: HashMap<String, String>? = null

    fun lookup(ctx: Context, prefix6: String): String? {
        val t = table ?: synchronized(this) {
            table ?: load(ctx).also { table = it }
        }
        return t[prefix6]
    }

    private fun load(ctx: Context): HashMap<String, String> {
        val map = HashMap<String, String>(45000)
        runCatching {
            ctx.assets.open("oui.txt").bufferedReader().useLines { lines ->
                for (l in lines) {
                    val i = l.indexOf('\t')
                    if (i == 6) map[l.substring(0, 6)] = l.substring(7)
                }
            }
        }
        return map
    }
}
