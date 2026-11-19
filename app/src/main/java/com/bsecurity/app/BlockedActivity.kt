package com.bsecurity.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class BlockedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("package").orEmpty()
        val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(36, 60, 36, 60)
            setBackgroundColor(Color.rgb(12,18,33))
        }
        layout.addView(TextView(this).apply {
            text = "B-Security\n\n$name\n\n" + if (intent.getBooleanExtra("limit", false)) "Alcanzaste el tiempo de hoy." else "Esta app tiene una pausa parental."
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        layout.addView(Button(this).apply { text = "Volver al inicio"; setOnClickListener {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
        } })
        layout.addView(Button(this).apply { text = "Abrir B-Security"; setOnClickListener {
            startActivity(Intent(this@BlockedActivity, MainActivity::class.java)); finish()
        } })
        setContentView(layout)
    }
}
