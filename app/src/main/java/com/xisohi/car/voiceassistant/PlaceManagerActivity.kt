package com.xisohi.car.voiceassistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xisohi.car.voiceassistant.core.PlaceMatcher
import com.xisohi.car.voiceassistant.databinding.ActivityPlaceManagerBinding

class PlaceManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaceManagerBinding
    private lateinit var placeMatcher: PlaceMatcher
    private lateinit var adapter: PlaceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 PlaceMatcher（会加载内置+自定义词库）
        placeMatcher = PlaceMatcher(this)

        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 添加地名按钮
        binding.btnAddPlace.setOnClickListener {
            addPlace()
        }

        // 初始化 RecyclerView
        adapter = PlaceAdapter(
            places = emptyList(),
            isBuiltin = { name -> placeMatcher.isBuiltin(name) },
            onDelete = { name -> deletePlace(name) }
        )
        binding.rvPlaces.layoutManager = LinearLayoutManager(this)
        binding.rvPlaces.adapter = adapter

        // 刷新列表
        refreshPlaces()

        // 自动弹出输入法（延迟300ms，等窗口准备好）
        binding.etPlaceName.postDelayed({
            binding.etPlaceName.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etPlaceName, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }, 300)
    }

    private fun addPlace() {
        val name = binding.etPlaceName.text?.toString()?.trim() ?: ""
        val pinyin = binding.etPlacePinyin.text?.toString()?.trim() ?: ""

        if (name.isBlank()) {
            Toast.makeText(this, "请输入词语", Toast.LENGTH_SHORT).show()
            return
        }

        val success = placeMatcher.addPlace(name, pinyin)
        if (success) {
            Toast.makeText(this, "已添加：$name", Toast.LENGTH_SHORT).show()
            binding.etPlaceName.text?.clear()
            binding.etPlacePinyin.text?.clear()
            refreshPlaces()
        } else {
            Toast.makeText(this, "添加失败，词语可能已存在", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePlace(name: String) {
        val success = placeMatcher.removePlace(name)
        if (success) {
            Toast.makeText(this, "已删除：$name", Toast.LENGTH_SHORT).show()
            refreshPlaces()
        } else {
            Toast.makeText(this, "内置词不能删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPlaces() {
        val allPlaces = placeMatcher.getAllPlaces()
        // 只显示自定义热词，内置的不显示
        val customPlaces = allPlaces.filter { !placeMatcher.isBuiltin(it.name) }
        binding.tvStats.text = "自定义热词：${customPlaces.size} 个"
        adapter.updatePlaces(customPlaces)
    }

    // ========== RecyclerView Adapter ==========
    class PlaceAdapter(
        private var places: List<PlaceMatcher.Place>,
        private val isBuiltin: (String) -> Boolean,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<PlaceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvPlaceName)
            val tvPinyin: TextView = view.findViewById(R.id.tvPlacePinyin)
            val tvTag: TextView = view.findViewById(R.id.tvTag)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_place, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val place = places[position]
            holder.tvName.text = place.name
            holder.tvPinyin.text = place.pinyin

            val builtin = isBuiltin(place.name)
            holder.tvTag.text = if (builtin) "内置" else "自定义"
            holder.tvTag.setTextColor(if (builtin) 0xFF6B7280.toInt() else 0xFF52C41A.toInt())

            // 内置地名不能删除
            holder.btnDelete.visibility = if (builtin) View.GONE else View.VISIBLE
            holder.btnDelete.setOnClickListener {
                onDelete(place.name)
            }
        }

        override fun getItemCount(): Int = places.size

        fun updatePlaces(newPlaces: List<PlaceMatcher.Place>) {
            places = newPlaces
            notifyDataSetChanged()
        }
    }
}
