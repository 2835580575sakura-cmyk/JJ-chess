package dev.sakura.xiangqi.app
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
class DiagnosticProvider:ContentProvider() {
    override fun onCreate()=true
    private fun file(uri:Uri):File { val name=requireNotNull(uri.lastPathSegment); require(name.matches(Regex("[0-9a-f-]{36}\\.zip"))); return File(requireNotNull(context).cacheDir,"diagnostics/$name").also { require(it.isFile) } }
    override fun getType(uri:Uri)="application/zip"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor { require(mode=="r"); return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor { val f=file(uri); val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE); return MatrixCursor(columns).apply { addRow(columns.map<String,Any?> { if(it==OpenableColumns.SIZE)f.length() else if(it==OpenableColumns.DISPLAY_NAME)f.name else null }.toTypedArray()) } }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
