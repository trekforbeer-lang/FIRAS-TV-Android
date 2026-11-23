package com.firas.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 8, 8)
        window.navigationBarColor = Color.rgb(8, 8, 8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(8, 8, 8))
        }
        root.addView(TextView(this).apply {
            text = "FIRAS TV"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "تلفزيونك في مكان واحد"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "فتح المشغل"
            setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "إدارة القنوات (Admin)"
            setOnClickListener { startActivity(Intent(this@MainActivity, AdminActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        setContentView(root)
    }
}