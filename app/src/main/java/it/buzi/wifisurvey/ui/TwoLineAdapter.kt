package it.buzi.wifisurvey.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import it.buzi.wifisurvey.databinding.ItemTwoLinesBinding

/** Generic two-line list adapter with an overflow button. */
class TwoLineAdapter<T>(
    private val title: (T) -> String,
    private val subtitle: (T) -> String,
    private val onClick: (T) -> Unit,
    private val onMore: ((T, View) -> Unit)? = null
) : RecyclerView.Adapter<TwoLineAdapter<T>.VH>() {

    var items: List<T> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    inner class VH(val b: ItemTwoLinesBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTwoLinesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.title.text = title(item)
        holder.b.subtitle.text = subtitle(item)
        holder.b.itemRoot.setOnClickListener { onClick(item) }
        if (onMore == null) {
            holder.b.more.visibility = View.GONE
        } else {
            holder.b.more.visibility = View.VISIBLE
            holder.b.more.setOnClickListener { onMore.invoke(item, it) }
        }
    }
}
