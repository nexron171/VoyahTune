package com.bms.monitor

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bms.monitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private val tempFragment = TempFragment()
    private val cellsFragment = CellsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            show(tempFragment)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                // На фрейме напряжений опрос температур ставится на паузу,
                // на фрейме температур — возобновляется
                R.id.nav_temp -> { show(tempFragment); viewModel.resumeTemperaturePolling(); true }
                R.id.nav_cells -> { show(cellsFragment); viewModel.pauseTemperaturePolling(); true }
                else -> false
            }
        }

        // Фоновый опрос температур стартует сразу
        viewModel.startTemperaturePolling()
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }
}
