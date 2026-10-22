package dev.sakura.xiangqi.app
import android.Manifest
import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.*

class MainActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var watching:Job?=null
    private lateinit var status:TextView; private lateinit var player:Spinner; private lateinit var bottom:Spinner; private lateinit var turn:Spinner; private lateinit var budget:Spinner
    private lateinit var store:SettingsStore
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); store=SettingsStore(this)
        val saved=store.load(); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(24),dp(20),dp(20)) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text="象棋分析助手"; textSize=27f })
        root.addView(TextView(this).apply { text="适用于 JJ 象棋人机练习。手机离线计算，按箭头手动走棋。\n开始后切换到 JJ；首次识别请点悬浮窗「校验」。"; textSize=15f })
        fun choice(label:String,values:List<String>,selected:Int):Spinner { root.addView(TextView(this).apply { text=label; setPadding(0,dp(12),0,0) }); return Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,values); setSelection(selected); root.addView(this) } }
        player=choice("我执哪一方",listOf("红方","黑方"),if(saved.playerSide==Side.RED)0 else 1)
        bottom=choice("屏幕下方是哪一方",listOf("红方在下","黑方在下"),if(saved.orientation==Orientation.RED_BOTTOM)0 else 1)
        turn=choice("第一次确认时轮到谁走",listOf("红方先走","黑方先走"),if(saved.initialTurn==Side.RED)0 else 1)
        budget=choice("每步思考时间",listOf("快速 · 3 秒","默认 · 15 秒","加强 · 30 秒"),listOf(3000,15000,30000).indexOf(saved.movetimeMs).coerceAtLeast(0))
        root.addView(TextView(this).apply { text="更长时间通常计算更深入，也更耗电。修改这些设置后需停止并重新开始。"; textSize=13f })
        fun button(label:String,action:()->Unit) { root.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("开始分析") { begin() }
        button("校验棋子 / 当前行棋方") { editBoard() }
        button("校准棋盘位置") { calibrate() }
        button("恢复上次历史（需核对当前棋盘）") { restoreCandidate() }
        button("导出棋盘诊断") { exportDiagnostic() }
        button("暂停 / 继续") { AssistantRuntime.current?.pauseToggle() }
        button("返回 JJ 继续") { if(AssistantRuntime.current!=null)moveTaskToBack(true) else toast("请先开始分析") }
        button("停止录屏和分析") { AssistantRuntime.current?.stop(); status.text="已停止" }
        status=TextView(this).apply { text="尚未开始"; textSize=16f; setPadding(0,dp(16),0,0) }; root.addView(status)
        root.addView(TextView(this).apply { text="仅针对所提供视频中的木纹棋盘样式。换皮肤、翻转、窗口模式或识别有误时，请校准并逐格校验。"; textSize=13f; setPadding(0,dp(16),0,dp(20)) })
    }
    private fun readConfig()=SessionConfig(playerSide=if(player.selectedItemPosition==0)Side.RED else Side.BLACK,movetimeMs=listOf(3000,15000,30000)[budget.selectedItemPosition],orientation=if(bottom.selectedItemPosition==0)Orientation.RED_BOTTOM else Orientation.BLACK_BOTTOM,initialTurn=if(turn.selectedItemPosition==0)Side.RED else Side.BLACK,options=store.load().options.copy(threads=minOf(store.load().options.threads,Runtime.getRuntime().availableProcessors().coerceAtLeast(1))))
    private fun begin() {
        if(AssistantRuntime.current!=null) { toast("当前分析正在运行，请先停止再修改设置"); return }
        if(!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))); toast("请允许显示悬浮窗，然后再点开始分析"); return }
        store.save(readConfig())
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),8)
        @Suppress("DEPRECATION") startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),7)
    }
    @Deprecated("Platform consent result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=7)return
        if(resultCode!=RESULT_OK||data==null) { status.text="未获得录屏授权，可以重新开始"; return }
        if(AssistantRuntime.current!=null)return
        val runtime=AssistantRuntime.create(this,store.load()); runtime.uiVisible(true); watch()
        try { startForegroundService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("consent",data)); status.text="录屏已开启，请切换到 JJ 人机棋盘"; moveTaskToBack(true) }
        catch(t:Throwable) { runtime.stop(); status.text="录屏启动失败：${t.message}" }
    }
    override fun onResume() { super.onResume(); AssistantRuntime.current?.uiVisible(true); if(AssistantRuntime.current==null)AssistantRuntime.lastMessage?.let { status.text=it }; watch() }
    override fun onPause() { AssistantRuntime.current?.uiVisible(false); super.onPause() }
    private fun watch() { watching?.cancel(); val runtime=AssistantRuntime.current?:return; watching=scope.launch { runtime.coordinator.state.collect { value -> status.text=value.message+if(value.position!=null)"\n"+(if(value.historyComplete)"完整行棋历史" else "历史不完整：重复局面判断可能受限") else "" } } }
    private fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun editBoard() {
        val runtime=AssistantRuntime.current?:return toast("请先开始分析")
        val state=runtime.coordinator.state.value; val initial=state.position?:return toast("请先返回 JJ，等待棋盘稳定后再校验")
        val cal=state.calibration?:return toast("尚未找到棋盘，请先校准")
        val editor=BoardEditorState(initial); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),0,dp(8),0) }
        root.addView(TextView(this).apply { text="逐格核对，点棋子可修改。轮到谁走必须手动确认。" })
        val height=minOf((resources.displayMetrics.widthPixels*.95f*10/9).toInt(),(resources.displayMetrics.heightPixels*.50f).toInt())
        root.addView(BoardEditorView(this,editor,cal.orientation),LinearLayout.LayoutParams(-1,height))
        val who=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("轮到红方走","轮到黑方走")); setSelection(if(initial.sideToMove==Side.RED)0 else 1) }; root.addView(who)
        val indices=state.pendingHistoryIndices
        val restore=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("作为新局面确认（历史不完整）")+indices.map { "恢复到第 $it 手之前的历史" }) }; if(indices.isNotEmpty())root.addView(restore)
        val error=TextView(this); root.addView(error)
        val dialog=AlertDialog.Builder(this).setTitle("校验当前棋盘").setView(root).setNegativeButton("取消",null).setPositiveButton("确认",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            editor.sideToMove=if(who.selectedItemPosition==0)Side.RED else Side.BLACK
            val snapshot=editor.snapshot().getOrElse { error.text=it.message; return@setOnClickListener }
            val index=if(indices.isNotEmpty()&&restore.selectedItemPosition>0)indices[restore.selectedItemPosition-1] else null
            scope.launch { runtime.coordinator.confirm(snapshot,cal,index).join(); val result=runtime.coordinator.state.value; if(result.phase==SessionPhase.ERROR)error.text=result.message else { dialog.dismiss(); moveTaskToBack(true) } }
        } }; dialog.show()
    }
    private fun calibrate() {
        val runtime=AssistantRuntime.current?:return toast("请先开始分析")
        val frame=runtime.preview?:return toast("请先切换到 JJ，保留一张棋盘画面")
        val current=runtime.coordinator.state.value.calibration?:dev.sakura.xiangqi.vision.GridLocator.locate(frame)?:return toast("未找到棋盘")
        val view=CalibrationView(this,frame,current)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text="拖动蓝色两角，对齐左上和右下的棋盘交点（不含棋盘外框）。" }); addView(view,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.65f).toInt())) }
        AlertDialog.Builder(this).setTitle("校准棋盘").setView(root).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
            val (w,h)=runtime.overlay.screenSize(); val r=view.rect
            runtime.coordinator.recalibrate(current.copy(captureRect=r,displayRect=FloatRect(r.left*w/frame.width,r.top*h/frame.height,r.right*w/frame.width,r.bottom*h/frame.height)))
            toast("已保存；返回 JJ 稳定识别后再校验棋子")
        }.show()
    }
    private fun restoreCandidate() {
        val runtime=AssistantRuntime.current?:return toast("请先开始并识别当前棋盘")
        val candidate=runtime.recoveryCandidate?:return toast("没有可用的历史记录")
        val state=runtime.coordinator.state.value; val cal=state.calibration?:return toast("请先识别当前棋盘")
        if(state.position?.board!=candidate.snapshot.board)return toast("上次历史与当前棋盘不同，不能恢复")
        AlertDialog.Builder(this).setTitle("确认历史属于当前这局")
            .setMessage("已匹配棋子位置。请确认当前轮到${if(candidate.snapshot.sideToMove==Side.RED)"红" else "黑"}方走，且与上次是同一局。")
            .setNegativeButton("取消",null).setPositiveButton("确认恢复") { _,_ -> scope.launch { runtime.coordinator.confirmCandidate(candidate,cal).join(); if(runtime.coordinator.state.value.phase==SessionPhase.ERROR)toast(runtime.coordinator.state.value.message) else moveTaskToBack(true) } }.show()
    }
    private fun exportDiagnostic() {
        val runtime=AssistantRuntime.current?:return toast("请先开始分析")
        val frame=runtime.preview?:return toast("还没有 JJ 棋盘画面"); val rect=runtime.coordinator.state.value.calibration?.captureRect?:return toast("请先校准棋盘")
        scope.launch {
            runCatching {
                val uri=withContext(Dispatchers.IO) { DiagnosticExporter(this@MainActivity).export(DiagnosticExporter.crop(frame,rect),listOf(DiagnosticEvent(SystemClock.elapsedRealtime(),DiagnosticCode.NEEDS_CONFIRMATION))) }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享棋盘诊断"))
            }.onFailure { toast("导出失败，请检查剩余存储空间") }
        }
    }
    override fun onDestroy() { watching?.cancel(); scope.cancel(); super.onDestroy() }
}
