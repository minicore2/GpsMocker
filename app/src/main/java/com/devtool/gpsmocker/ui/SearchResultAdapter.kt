package com.devtool.gpsmocker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.devtool.gpsmocker.databinding.ItemSearchResultBinding
import com.devtool.gpsmocker.utils.SearchResult

class SearchResultAdapter(
    private val onClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultAdapter.VH>(DIFF) {

    inner class VH(val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.b.tvShortName.text    = r.shortName
        h.b.tvLocationMeta.text = listOfNotNull(
            r.city.takeIf { it.isNotEmpty() },
            r.country.takeIf { it.isNotEmpty() }
        ).joinToString("・")
        h.b.root.setOnClickListener { onClick(r) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(a: SearchResult, b: SearchResult) = a.displayName == b.displayName
            override fun areContentsTheSame(a: SearchResult, b: SearchResult) = a == b
        }
    }
}
