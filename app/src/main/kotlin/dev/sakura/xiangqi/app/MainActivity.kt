package dev.sakura.xiangqi.app
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(TextView(this).apply { text = "象棋分析助手\n本地引擎与棋盘识别"; textSize = 24f; setPadding(32, 64, 32, 32) }) }
}
