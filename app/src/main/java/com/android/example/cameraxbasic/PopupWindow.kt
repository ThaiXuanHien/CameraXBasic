package com.android.example.cameraxbasic

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.example.cameraxbasic.databinding.DialogListItemBinding
import com.android.example.cameraxbasic.databinding.ItemListBinding

class PopupWindow(
    private val view: View,
    private val onItemCLicked: (Int) -> Unit,
    private val listItem: List<String>
) : PopupWindow(view.context) {

    init {
        val binding = DialogListItemBinding.inflate(LayoutInflater.from(view.context))

        binding.run {
            rcvFilter.layoutManager = LinearLayoutManager(root.context)

            rcvFilter.adapter = ListAdapter(listItem) {
                onItemCLicked(it)
                dismiss()
            }
        }
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        isFocusable = true

        contentView = binding.root

    }

    fun show() {
        showAsDropDown(view, -4,
            -view.height - -4)
    }

    class ListAdapter(
        private var listItem: List<String>,
        private val onItemCLicked: (Int) -> Unit
    ) :
        RecyclerView.Adapter<ItemViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return ItemViewHolder(
                ItemListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount(): Int = listItem.size

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            holder.binding.run {

                txtItem.text = listItem[position]

                root.setOnClickListener {
                    onItemCLicked(position)
                }
            }
        }

    }

    class ItemViewHolder(val binding: ItemListBinding) :
        RecyclerView.ViewHolder(binding.root)
}