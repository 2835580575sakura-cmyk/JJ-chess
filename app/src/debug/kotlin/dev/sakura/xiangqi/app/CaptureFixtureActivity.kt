package dev.sakura.xiangqi.app
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.*
/** Debug-only, no launcher or exported entry point. The tester grants a fresh system token. */
class CaptureFixtureActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(object:View(this) {
            val bitmap=assets.open("capture-fixture.png").use { BitmapFactory.decodeStream(it) }
            override fun onDraw(canvas:Canvas) {
                canvas.drawColor(Color.rgb(235,220,190))
                // Source crop (8,330,576,945), grid (40,366,545,909) in a 576 x 1280 frame.
                canvas.drawBitmap(bitmap,null,RectF(8f/576*width,330f/1280*height,width.toFloat(),945f/1280*height),null)
            }
        })
        @Suppress("DEPRECATION") startActivityForResult(if(android.os.Build.VERSION.SDK_INT>=34)getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),9)
    }
    @Deprecated("Fresh manual system consent") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==9 && resultCode==RESULT_OK && data!=null) {
            AssistantRuntime.create(this,SessionConfig(movetimeMs=3000)).uiVisible(false)
            startForegroundService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("consent",data))
        }
    }
}
