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
import dev.sakura.xiangqi.vision.GridLocator
import dev.sakura.xiangqi.vision.TemplateClassifier
import kotlinx.coroutines.*

class MainActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var watching:Job?=null
    private lateinit var status:TextView; private lateinit var player:Spinner; private lateinit var bottom:Spinner; private lateinit var turn:Spinner; private lateinit var budget:Spinner; private lateinit var loadMode:Spinner
    private lateinit var thermalPause:CheckBox
    private lateinit var store:SettingsStore
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); store=SettingsStore(this)
        val saved=store.load(); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(24),dp(20),dp(20)) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text="象棋分析助手"; textSize=27f })
        root.addView(TextView(this).apply { text="适用于 JJ 象棋人机练习。手机离线计算，按箭头手动走棋。\n开始后切换到 JJ；首次点悬浮窗「校验」。新棋子样式可从标准开局适配一次。"; textSize=15f })
        fun choice(label:String,values:List<String>,selected:Int):Spinner { root.addView(TextView(this).apply { text=label; setPadding(0,dp(12),0,0) }); return Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,values); setSelection(selected); root.addView(this) } }
        player=choice("我执哪一方",listOf("红方","黑方"),if(saved.playerSide==Side.RED)0 else 1)
        bottom=choice("屏幕下方是哪一方",listOf("红方在下","黑方在下"),if(saved.orientation==Orientation.RED_BOTTOM)0 else 1)
        turn=choice("第一次确认时轮到谁走",listOf("红方先走","黑方先走"),if(saved.initialTurn==Side.RED)0 else 1)
        budget=choice("每步思考时间",listOf("快速 · 3 秒","默认 · 15 秒","加强 · 30 秒"),listOf(3000,15000,30000).indexOf(saved.movetimeMs).coerceAtLeast(0))
        loadMode=choice("计算负载",listOf("省热 · 2 线程（推荐）","高性能 · 4 线程"),if(saved.options.threads>=4)1 else 0)
        thermalPause=CheckBox(this).apply { text="手机过热时自动暂停插件"; isChecked=saved.pauseOnOverheat; root.addView(this) }
        root.addView(TextView(this).apply { text="自动暂停默认关闭，关闭后插件继续计算；手机系统自身的降频和温控不受影响。省热模式计算速度较低；长时间或高性能模式更易发热。修改设置后需停止并重新开始。"; textSize=13f })
        fun button(label:String,action:()->Unit) { root.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("开始分析") { begin() }
        button("校验棋子 / 当前行棋方") { editBoard() }
        button("适配棋子（标准开局）") { adaptOpening() }
        button("校准棋盘位置") { calibrate() }
        button("恢复上次历史（需核对当前棋盘）") { restoreCandidate() }
        button("导出棋盘诊断") { exportDiagnostic() }
        button("暂停 / 继续") { AssistantRuntime.current?.pauseToggle() }
        button("返回 JJ 继续") { if(AssistantRuntime.current!=null)moveTaskToBack(true) else toast("请先开始分析") }
        button("停止录屏和分析") { AssistantRuntime.current?.stop(); status.text="已停止" }
        status=TextView(this).apply { text="尚未开始"; textSize=16f; setPadding(0,dp(16),0,0) }; root.addView(status)
        root.addView(TextView(this).apply { text="棋子识别失败时，在 JJ 点「重来」，保留 32 枚棋子的标准开局，再回到这里适配。适配会保存，同一棋子样式通常只需一次；换皮肤后请重新适配。中途接入时，请校验棋子和当前行棋方。"; textSize=13f; setPadding(0,dp(16),0,dp(20)) })
    }
    private fun readConfig()=SessionConfig(playerSide=if(player.selectedItemPosition==0)Side.RED else Side.BLACK,movetimeMs=listOf(3000,15000,30000)[budget.selectedItemPosition],orientation=if(bottom.selectedItemPosition==0)Orientation.RED_BOTTOM else Orientation.BLACK_BOTTOM,initialTurn=if(turn.selectedItemPosition==0)Side.RED else Side.BLACK,options=store.load().options.copy(threads=minOf(listOf(2,4)[loadMode.selectedItemPosition],Runtime.getRuntime().availableProcessors().coerceAtLeast(1))),pauseOnOverheat=thermalPause.isChecked)
    private fun begin() {
        if(AssistantRuntime.current!=null) { toast("当前分析正在运行，请先停止再修改设置"); return }
        if(!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))); toast("请允许显示悬浮窗，然后再点开始分析"); return }
        store.save(readConfig())
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),8)
        @Suppress("DEPRECATION") startActivityForResult(if(Build.VERSION.SDK_INT>=34)getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),7)
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
        val state=runtime.coordinator.state.value; val initial=state.position?:return recognitionRecovery()
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
    private fun recognitionRecovery() {
        AlertDialog.Builder(this).setTitle("棋子尚未识别")
            .setMessage("可以从标准开局适配当前棋子样式。请先在 JJ 点「重来」，保留 32 枚棋子的开局画面；如果棋盘网格位置不对，请校准棋盘。")
            .setPositiveButton("适配标准开局") { _,_ -> adaptOpening() }
            .setNeutralButton("校准棋盘") { _,_ -> calibrate() }
            .setNegativeButton("取消",null).show()
    }
    private fun adaptOpening() {
        val runtime=AssistantRuntime.current?:return toast("请先开始分析")
        val frame=runtime.preview?:return toast("请先切换到 JJ，点「重来」保留标准开局，再回到助手")
        val located=GridLocator.locate(frame)?:runtime.coordinator.state.value.calibration?:return toast("未找到棋盘，请先校准")
        val (w,h)=runtime.overlay.screenSize(); val r=located.captureRect
        val current=located.copy(orientation=runtime.config.orientation,displayRect=FloatRect(r.left*w/frame.width,r.top*h/frame.height,r.right*w/frame.width,r.bottom*h/frame.height))
        val view=CalibrationView(this,frame,current)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(8),0,dp(8),0)
            addView(TextView(this@MainActivity).apply { text="请先在 JJ 点「重来」，确认下方预览是 32 枚棋子的标准开局，轮到红方走。中途局面不能用于适配。\n拖动蓝色两角，对齐左上和右下棋盘交点，不含外框。" })
            addView(view,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.55f).toInt()))
        }
        val error=TextView(this); root.addView(error)
        val dialog=AlertDialog.Builder(this).setTitle("适配当前棋子样式")
            .setView(ScrollView(this).apply { addView(root) }).setNegativeButton("取消",null)
            .setPositiveButton("确认标准开局并适配",null).create()
        var adaptationJob:Job?=null; var activeOperation:Job?=null
        dialog.setOnDismissListener { activeOperation?.cancel(); adaptationJob?.cancel() }
        dialog.setOnShowListener {
            val submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.setOnClickListener {
                submit.isEnabled=false; error.text="正在适配棋子…"
                val selected=view.rect
                val calibrated=current.copy(captureRect=selected,displayRect=FloatRect(selected.left*w/frame.width,selected.top*h/frame.height,selected.right*w/frame.width,selected.bottom*h/frame.height))
                adaptationJob=scope.launch {
                    try {
                        val adapted=withContext(Dispatchers.Default) { TemplateClassifier.fromOpening(frame,calibrated) }.getOrElse {
                            error.text=it.message?:"适配失败，请检查开局与棋盘交点"; return@launch
                        }
                        currentCoroutineContext().ensureActive()
                        if(!dialog.isShowing || AssistantRuntime.current!==runtime) { error.text="会话已停止，请重新开始"; return@launch }
                        activeOperation=runtime.coordinator.useTemplates(adapted,calibrated)
                        activeOperation!!.join(); activeOperation=null
                        currentCoroutineContext().ensureActive()
                        val prepared=runtime.coordinator.state.value
                        if(prepared.phase==SessionPhase.ERROR || prepared.phase==SessionPhase.STOPPED || AssistantRuntime.current!==runtime) {
                            error.text=prepared.message; return@launch
                        }
                        val confirmedCalibration=requireNotNull(prepared.calibration) { "尚未找到棋盘，请先校准" }
                        val opening=FenCodec.decode(START_FEN).getOrThrow()
                        if(!dialog.isShowing)return@launch
                        activeOperation=runtime.coordinator.confirm(opening,confirmedCalibration)
                        activeOperation!!.join(); activeOperation=null
                        currentCoroutineContext().ensureActive()
                        val result=runtime.coordinator.state.value
                        if(result.phase==SessionPhase.ERROR || result.phase==SessionPhase.STOPPED || AssistantRuntime.current!==runtime) {
                            error.text=result.message; return@launch
                        }
                        check(result.position?.board==opening.board && result.position?.sideToMove==Side.RED) { "开局尚未确认，请重试" }
                        if(!dialog.isShowing)return@launch
                        TemplateStore(filesDir).save(adapted)
                        store.saveCalibration(confirmedCalibration,frame.width,frame.height,w,h)
                        adaptationJob=null; dialog.dismiss(); toast("适配已保存，返回 JJ 后自动识别并分析"); moveTaskToBack(true)
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { error.text=e.message?:"适配失败，请重试" }
                    finally { activeOperation?.cancel(); activeOperation=null; submit.isEnabled=true }
                }
            }
        }
        dialog.show()
    }
    private fun calibrate() {
        val runtime=AssistantRuntime.current?:return toast("请先开始分析")
        val frame=runtime.preview?:return toast("请先切换到 JJ，保留一张棋盘画面")
        val current=runtime.coordinator.state.value.calibration?:dev.sakura.xiangqi.vision.GridLocator.locate(frame)?:return toast("未找到棋盘")
        val view=CalibrationView(this,frame,current)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text="拖动蓝色两角，对齐左上和右下的棋盘交点（不含棋盘外框）。" }); addView(view,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.65f).toInt())) }
        AlertDialog.Builder(this).setTitle("校准棋盘").setView(root).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
            val (w,h)=runtime.overlay.screenSize(); val r=view.rect
            val calibrated=current.copy(captureRect=r,displayRect=FloatRect(r.left*w/frame.width,r.top*h/frame.height,r.right*w/frame.width,r.bottom*h/frame.height))
            store.saveCalibration(calibrated,frame.width,frame.height,w,h)
            runtime.coordinator.recalibrate(calibrated)
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
        val sample=runtime.coordinator.diagnosticSample?:return toast("还没有经过稳定识别的棋盘，请先校准和识别")
        scope.launch {
            runCatching {
                val uri=withContext(Dispatchers.IO) { DiagnosticExporter(this@MainActivity).export(sample.crop(),listOf(DiagnosticEvent(SystemClock.elapsedRealtime(),DiagnosticCode.NEEDS_CONFIRMATION))) }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享棋盘诊断"))
            }.onFailure { toast("导出失败，请检查剩余存储空间") }
        }
    }
    override fun onDestroy() { watching?.cancel(); scope.cancel(); super.onDestroy() }
}
