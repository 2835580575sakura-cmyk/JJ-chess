package dev.sakura.xiangqi.app
class OverlaySamplingGate(private val clock:()->Long=System::nanoTime,private val requestHide:()->Unit) {
    private var hiddenAt:Long?=null
    private var pending=false
    private var fresh=0
    private var last=Long.MIN_VALUE
    private var next=Long.MIN_VALUE
    @Synchronized fun hideForSample():Long=clock().also { hiddenAt=it; fresh=0; pending=false }
    @Synchronized fun acceptFrame(timestampNs:Long):Boolean {
        if(timestampNs<=last)return false
        last=timestampNs
        val hidden=hiddenAt
        if(hidden!=null) {
            if(timestampNs<=hidden)return false
            fresh++
            if(fresh<2)return false
            hiddenAt=null; next=timestampNs+333333333L; return true
        }
        if(timestampNs<next)return false
        if(!pending) { pending=true; requestHide() }
        return false
    }
}
