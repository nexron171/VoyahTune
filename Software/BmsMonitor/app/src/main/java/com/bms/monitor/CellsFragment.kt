package com.bms.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bms.monitor.databinding.FragmentCellsBinding
import com.bms.monitor.databinding.ItemCellBinding

/** Фрейм 2: 100 ячеек в виде батареек, градиент 0..5 В (красный→зелёный). */
class CellsFragment : Fragment() {

    private var _b: FragmentCellsBinding? = null
    private val b get() = _b!!
    private val vm: MainViewModel by activityViewModels()
    private val adapter = CellAdapter()

    /** Минимальная ширина иконки (dp): колонки = сколько таких помещается по ширине.
     *  lazy — resources доступны только после attach фрагмента. */
    private val minIconW by lazy { (146 * resources.displayMetrics.density).toInt() }
    private var currentCols = 1
    private lateinit var layoutManager: GridLayoutManager

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCellsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        // Резиновая сетка: колонки считаются от фактической ширины контейнера
        // (в freeform displayMetrics — это весь экран, а не окно), иконка
        // растягивается на ширину колонки (item_cell: match_parent + adjustViewBounds)
        // Экземпляр фрагмента переиспользуется MainActivity, а view пересоздаётся
        // при каждом переходе — сбрасываем состояние, привязанное к старой view,
        // иначе currentCols «совпадёт» и новая сетка останется в 1 колонку
        currentCols = 1
        adapter.cellHeight = 0
        adapter.textPx = 0f
        layoutManager = GridLayoutManager(requireContext(), 1)
        b.cellsGrid.layoutManager = layoutManager
        b.cellsGrid.adapter = adapter

        b.btnRefresh.setOnClickListener { vm.refreshCells() }
        vm.cells.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            updateStretch()
            if (list.isEmpty()) {
                b.statMin.text = "Минимальный заряд: —"
                b.statMax.text = "Максимальный заряд: —"
                b.statDiff.text = "Дисбаланс: —"
                b.statMin.setTextColor(ColorUtils.cellColorT(0f))
                b.statMax.setTextColor(ColorUtils.cellColorT(1f))
                b.statDiff.setTextColor(ColorUtils.imbalanceColor(0.0))
            } else {
                val mn = list.min()
                val mx = list.max()
                val diff = mx - mn
                b.statMin.text = String.format("Минимальный заряд: %.3f В", mn)
                b.statMax.text = String.format("Максимальный заряд: %.3f В", mx)
                b.statDiff.text = String.format("Дисбаланс: %.3f В", diff)
                // Цвет текста = цвет значения по нормализованному градиенту:
                // минимальный — край градиента (красный), максимальный — (зелёный)
                b.statMin.setTextColor(ColorUtils.cellColorT(0f))
                b.statMax.setTextColor(ColorUtils.cellColorT(1f))
                b.statDiff.setTextColor(ColorUtils.imbalanceColor(diff))
            }
        }
        vm.cellsStatus.observe(viewLifecycleOwner) { b.cellsStatus.text = it }
        vm.cellsLoading.observe(viewLifecycleOwner) { loading ->
            b.btnRefresh.isEnabled = !loading
            b.cellsProgress.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // Пересчёт при каждом изменении размеров (freeform: окно тянут за край)
        // Изменения откладываются через post: requestLayout() внутри прохода
        // layout игнорируется, и сетка оставалась с устаревшей раскладкой
        b.cellsGrid.addOnLayoutChangeListener { v, l, t, r, btm, ol, ot, or, ob ->
            if (r - l == or - ol && btm - t == ob - ot) return@addOnLayoutChangeListener
            v.post { if (_b != null) updateStretch() }
        }
    }

    /**
     * Вписывание всех ячеек в окно (в т.ч. freeform): берётся наименьшее число
     * колонок, при котором все ряды помещаются по высоте при пропорции иконки
     * 48:16.8, но не меньше, чем колонок шириной minIconW. Иконка уменьшается
     * вместе с колонкой, ряды растягиваются на всю высоту сетки, текст
     * масштабируется по фактическому размеру иконки.
     */
    private fun updateStretch() {
        val grid = b.cellsGrid
        val w = grid.width
        val h = grid.height
        if (w <= 0 || h <= 0) return
        val n = maxOf(adapter.itemCount, CELLS)
        var cols = (w / minIconW).coerceIn(1, n)
        while (cols < n && rowsFor(n, cols) * (w / cols) * ASPECT > h) cols++
        if (cols != currentCols) {
            currentCols = cols
            layoutManager.spanCount = cols
        }
        val rows = rowsFor(n, cols)
        val colW = w / cols
        val newHeight = if (rows * colW * ASPECT <= h) h / rows else 0
        // Видимая ширина иконки: по колонке или по высоте ряда (adjustViewBounds)
        val iconW = if (newHeight > 0) minOf(colW.toFloat(), newHeight / ASPECT) else colW.toFloat()
        val textPx = minOf(iconW * TEXT_K, maxTextPx)
        if (newHeight != adapter.cellHeight || textPx != adapter.textPx) {
            adapter.cellHeight = newHeight
            adapter.textPx = textPx
            adapter.notifyDataSetChanged()
        }
    }

    private fun rowsFor(n: Int, cols: Int) = (n + cols - 1) / cols

    /** Исходный размер текста ячейки (16sp) — верхняя граница при масштабировании. */
    private val maxTextPx by lazy { 16 * resources.displayMetrics.scaledDensity }

    private companion object {
        const val CELLS = 100
        /** Высота иконки к ширине (вектор 48:16.8). */
        const val ASPECT = 16.8f / 48f
        /** Размер текста к ширине иконки: «3.740 V» укладывается в корпус батарейки. */
        const val TEXT_K = 0.15f
    }

    override fun onDestroyView() {
        // Отвязываем адаптер (он живёт дольше view), чтобы не держать старую сетку
        b.cellsGrid.adapter = null
        super.onDestroyView()
        _b = null
    }

    private class CellAdapter : RecyclerView.Adapter<CellAdapter.VH>() {
        private var items: List<Double> = emptyList()
        private var minV = 0.0
        private var maxV = 0.0
        var cellHeight: Int = 0
        var textPx: Float = 0f

        fun submit(list: List<Double>) {
            items = list
            minV = if (list.isEmpty()) 0.0 else list.min()
            maxV = if (list.isEmpty()) 0.0 else list.max()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val item = ItemCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(item)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = items[position]
            // Высота строки: принудительная (растяжка) или естественная —
            // сбрасываем и при уменьшении окна, иначе recycled-элемент
            // сохранит старую высоту
            val lp = holder.b.root.layoutParams
            val h = if (cellHeight > 0) cellHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height != h) {
                lp.height = h
                holder.b.root.layoutParams = lp
            }
            if (textPx > 0f && holder.b.cellValue.textSize != textPx)
                holder.b.cellValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
            holder.b.cellValue.text = String.format("%.3f V", v)
            // Градиент между min и max пачки: минимальная — красная,
            // максимальная — зелёная, остальные — пропорционально
            val t = if (maxV > minV) ((v - minV) / (maxV - minV)).toFloat() else 0.5f
            holder.b.cellIcon.setColorFilter(ColorUtils.cellColorT(t))
        }

        class VH(val b: ItemCellBinding) : RecyclerView.ViewHolder(b.root)
    }
}
