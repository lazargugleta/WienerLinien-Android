package com.wienerlinien.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wienerlinien.app.databinding.ActivityTransitMapBinding

class TransitMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransitMapBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransitMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
