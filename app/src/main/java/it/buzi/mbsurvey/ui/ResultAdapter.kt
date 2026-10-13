package it.buzi.mbsurvey.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import it.buzi.mbsurvey.databinding.ItemResultBinding

/** One result card: title, optional value on the right, subtitle, optional gradient bar and badge. */
class ResultItem(
    var title: String,
    var subtitle: String = "",
    var value: String = "",
    var bar: Float? = null,
    var barColor: Int = Color.rgb(11, 110, 79),
    var badge: String? = null,
    var badgeColor: Int = Color.rgb(11, 110, 79),
    val key: String? = null
) {
    fun asText(): String = listOf(badge?.let { "[$it]" }, title, value, subtitle.replace('\n', ' ')).filter { !it.isNullOrBlank() }.joinToString("  ")
}

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.VH>() {
    val items = ArrayList<ResultItem>()

    inner class VH(val b: ItemResultBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val it = items[i]
        h.b.title.text = it.title
        h.b.value.text = it.value
        h.b.value.visibility = if (it.value.isBlank()) View.GONE else View.VISIBLE
        h.b.subtitle.text = it.subtitle
        h.b.subtitle.visibility = if (it.subtitle.isBlank()) View.GONE else View.VISIBLE
        if (it.bar == null) h.b.bar.visibility = View.GONE else { h.b.bar.visibility = View.VISIBLE; h.b.bar.fraction = it.bar!!; h.b.bar.color = it.barColor }
        if (it.badge == null) h.b.badge.visibility = View.GONE else {
            h.b.badge.visibility = View.VISIBLE; h.b.badge.text = it.badge
            h.b.badge.background.setTint(it.badgeColor)
        }
    }

    fun clear() { items.clear(); notifyDataSetChanged() }
    fun add(item: ResultItem): ResultItem { items.add(item); notifyItemInserted(items.size - 1); return item }
    fun update(item: ResultItem) { val i = items.indexOf(item); if (i >= 0) notifyItemChanged(i) }
    fun replaceAll(list: List<ResultItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun text(): String = items.joinToString("\n") { it.asText() }
}
