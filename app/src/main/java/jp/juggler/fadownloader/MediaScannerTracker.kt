package jp.juggler.fadownloader

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
import jp.juggler.fadownloader.util.LogWriter

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class MediaScannerTracker(
	internal val context : Context,
	internal val log : LogWriter
) : MediaScannerConnection.MediaScannerConnectionClient
{
	internal class Item {
		
		var path : String? = null
		var mime_type : String? = null
	}
	
	internal val conn : MediaScannerConnection = MediaScannerConnection(context, this)
	
	internal val handler : Handler = Handler()
	
	private var last_connect_start : Long = 0
	
	@Volatile
	internal var is_dispose = false
	
	internal val queue = ConcurrentLinkedQueue<Item>()
	
	init {
		prepareConnection()
	}
	
	private val queue_reader : Runnable = object : Runnable {
		override fun run() {
			handler.removeCallbacks(this)
			while(true) {
				
				val item = queue.peek()
				
				if(item == null) {
					if(is_dispose) {
						conn.disconnect()
					}
					break
				}
				
				if(! prepareConnection()) {
					handler.postDelayed(this, 1000L)
					break
				}
				
				conn.scanFile(item.path, item.mime_type)
				
				queue.poll()
			}
		}
	}
	
	override fun onMediaScannerConnected() {
		handler.post(queue_reader)
	}
	
	override fun onScanCompleted(path : String, uri : Uri) {}
	
	internal fun dispose() {
		is_dispose = true
	}
	
	fun addFile(file : File?, mime_type : String) {
		if(file == null || ! file.isFile) return
		if(TextUtils.isEmpty(mime_type)) return
		val item = Item()
		item.path = file.absolutePath
		item.mime_type = mime_type
		queue.add(item)
		handler.post(queue_reader)
	}
	
	private fun prepareConnection() : Boolean {
		if(conn.isConnected) return true
		
		val now = SystemClock.elapsedRealtime()
		if(now - last_connect_start >= 5000L) {
			last_connect_start = now
			conn.connect()
		}
		return false
	}
	
}
