package jp.juggler.fadownloader

import android.app.Activity
import android.view.View
import android.widget.ListView
import jp.juggler.fadownloader.util.PagerAdapterBase

class PageRecord(activity : Activity, ignored : View) :
	PagerAdapterBase.PageViewHolder(activity, ignored) {
	
	private lateinit var listView : ListView
	internal lateinit var viewer : DownloadRecordViewer
	
	@Throws(Throwable::class)
	override fun onPageCreate(page_idx : Int, root : View) {
		listView = root.findViewById<View>(R.id.lvRecord) as ListView
		viewer = DownloadRecordViewer()
		
		if((activity as ActMain).is_start) {
			onStart()
		}
	}
	
	@Throws(Throwable::class)
	override fun onPageDestroy(page_idx : Int, root : View) {
		onStop()
	}
	
	internal fun onStart() {
		viewer.onStart(activity as ActMain, listView, ActMain.LOADER_ID_RECORD)
	}
	
	private fun onStop() {
		viewer.onStop()
	}
}
