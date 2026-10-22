package dev.sakura.xiangqi.app
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.sakura.xiangqi.vision.PixelFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object CaptureBus {
    val frames=MutableSharedFlow<PixelFrame>(extraBufferCapacity=1,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val events=MutableSharedFlow<CaptureEvent>(extraBufferCapacity=8)
    @Volatile var latest:PixelFrame?=null
    @Volatile var running=false
    @Volatile var stopRequest:(()->Unit)?=null
    @Volatile var sampleFilter:((Long)->Boolean)?=null
}
class CaptureService:Service() {
    val frames:SharedFlow<PixelFrame> get()=CaptureBus.frames
    private lateinit var thread:HandlerThread
    private lateinit var handler:Handler
    private lateinit var controller:ProjectionController
    private var lastSample=Long.MIN_VALUE
    @Volatile private var stopping=false
    override fun onCreate() {
        super.onCreate(); thread=HandlerThread("screen-frames").apply { start() }; handler=Handler(thread.looper)
        CaptureBus.stopRequest={ stopSelf() }
        controller=ProjectionController(object:ProjectionPlatform {
            override fun startForeground() { startCaptureForeground() }
            override fun open(resultCode:Int,data:Intent):ProjectionSession {
                val projection=checkNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode,data)) { "系统未授予录屏权限" }
                return AndroidProjection(projection)
            }
        }) { event -> CaptureBus.events.tryEmit(event); if(event is CaptureEvent.Stopped || event is CaptureEvent.Error) { CaptureBus.running=false; stopSelf() } }
    }
    private fun startCaptureForeground() {
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("analysis","棋盘分析",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"analysis").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("象棋分析助手正在读取棋盘").setContentText("点此校验棋盘；可随时停止").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
        startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION") val data=intent?.getParcelableExtra<Intent>("consent")
        if(data==null) { stopSelf(); return START_NOT_STICKY }
        if(intent.getIntExtra("resultCode",Activity.RESULT_CANCELED)!=Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        CaptureBus.running=true
        controller.begin(intent.getIntExtra("resultCode",Activity.RESULT_CANCELED),data)
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION") private inner class AndroidProjection(private val projection:MediaProjection):ProjectionSession {
        private var display:VirtualDisplay?=null
        private var reader:ImageReader?=null
        private var callback:MediaProjection.Callback?=null
        override fun registerStop(callback:()->Unit) {
            this.callback=object:MediaProjection.Callback() {
                override fun onStop() { callback() }
                override fun onCapturedContentResize(width:Int,height:Int) { controller.onResized(width,height) }
                override fun onCapturedContentVisibilityChanged(visible:Boolean) { CaptureBus.events.tryEmit(CaptureEvent.Visibility(visible)) }
            }.also { projection.registerCallback(it,handler) }
        }
        override fun createDisplay() {
            val metrics=DisplayMetrics(); (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            reader=ImageReader.newInstance(metrics.widthPixels,metrics.heightPixels,PixelFormat.RGBA_8888,2).apply {
                setOnImageAvailableListener({ source ->
                    if(stopping)return@setOnImageAvailableListener
                    val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val time=image.timestamp
                        val filter=CaptureBus.sampleFilter
                        if(filter!=null) { if(!filter(time))return@setOnImageAvailableListener }
                        else if(lastSample!=Long.MIN_VALUE && time-lastSample<333333333L)return@setOnImageAvailableListener
                        lastSample=time
                        val frame=FrameReader.readLatest(image); CaptureBus.latest=frame; CaptureBus.frames.tryEmit(frame)
                    } catch(t:Throwable) { if(!stopping)CaptureBus.events.tryEmit(CaptureEvent.Error("录屏画面读取失败，请重新开始")) }
                    finally { image.close() }
                },handler)
            }
            display=projection.createVirtualDisplay("XiangqiBoard",metrics.widthPixels,metrics.heightPixels,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
        }
        override fun releaseDisplay() { display?.release(); display=null }
        override fun releaseReader() { reader?.close(); reader=null }
        override fun stopProjection() { callback?.let { projection.unregisterCallback(it) }; projection.stop() }
    }
    override fun onDestroy() {
        stopping=true; CaptureBus.running=false; CaptureBus.stopRequest=null; CaptureBus.sampleFilter=null
        controller.stop(); CaptureBus.latest=null; stopForeground(STOP_FOREGROUND_REMOVE); thread.quitSafely(); super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
