package com.card.floatlock

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 15f
            text = "FloatLock\n\n" +
                "Pins Key Mapper's floating buttons to a fixed physical position across " +
                "screen rotation.\n\n" +
                "Setup:\n" +
                "1. Enable this module in LSPosed.\n" +
                "2. Set its scope to Key Mapper.\n" +
                "3. Force-stop Key Mapper (or reboot) so the hook loads.\n\n" +
                "This app has no settings of its own \u2014 the work happens inside Key " +
                "Mapper's process. Activity is logged to the LSPosed log (search \"FloatLock\")."
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        setContentView(scroll)
    }
}
