package jp.juggler.fadownloader

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ListView
import android.widget.TextView
import jp.juggler.fadownloader.table.LogData

import java.text.SimpleDateFormat
import java.util.Locale

class LogViewer {
	
	companion object {
		
		internal val date_fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault())
	}
	
	class Holder(
		val activity : AppCompatActivity,
		private val listView : ListView
	) : LoaderManager.LoaderCallbacks<Cursor> {
		
		private var loader : Loader<Cursor>? = null
		
		fun onStart(loader_id : Int) {
			loader = activity.supportLoaderManager.initLoader(loader_id, null, this)
		}
		
		fun onStop() : Int {
			val loader = this.loader
			if(loader != null) {
				activity.supportLoaderManager.destroyLoader(loader.id)
				this.loader = null
			}
			val rv = listView.firstVisiblePosition
			listView.adapter = null
			return rv
		}
		
		override fun onCreateLoader(id : Int, args : Bundle?) : Loader<Cursor> {
			return CursorLoader(
				activity,
				LogData.meta.content_uri,
				null,
				null,
				null,
				LogData.COL_TIME + " desc"
			)
		}
		
		override fun onLoadFinished(loader : Loader<Cursor>, cursor : Cursor) {
			val adapter = listView.adapter as? LogAdapter
			if(adapter == null) {
				listView.adapter = LogAdapter(cursor)
			} else {
				adapter.swapCursor(cursor)
			}
		}
		
		override fun onLoaderReset(loader : Loader<Cursor>) {
			val adapter = listView.adapter as? LogAdapter
			adapter?.swapCursor(null)
		}
		
		internal inner class LogAdapter(c : Cursor) : CursorAdapter(activity, c, false) {
			
			private val inflater : LayoutInflater = activity.layoutInflater
			private val colidx_time : Int = c.getColumnIndex(LogData.COL_TIME)
			private val colidx_message : Int = c.getColumnIndex(LogData.COL_MESSAGE)
			private val colidx_level : Int = c.getColumnIndex(LogData.COL_LEVEL)
			
			override fun newView(context : Context, cursor : Cursor, viewGroup : ViewGroup) : View {
				val root = inflater.inflate(R.layout.lv_log, viewGroup, false)
				val holder = ViewHolder(root)
				root.tag = holder
				return root
			}
			
			override fun bindView(view : View, context : Context, cursor : Cursor) {
				val holder = (view.tag as ViewHolder)
				
				val time = cursor.getLong(colidx_time)
				val message = cursor.getString(colidx_message)
				
				val level = cursor.getInt(colidx_level)
				val fg : Int
				fg = when {
					level >= LogData.LEVEL_FLOOD -> - 0x444445
					level >= LogData.LEVEL_HEARTBEAT -> - 0x666667
					level >= LogData.LEVEL_DEBUG -> - 0x888889
					level >= LogData.LEVEL_VERBOSE -> - 0xaaaaab
					level >= LogData.LEVEL_INFO -> - 0x1000000
					level >= LogData.LEVEL_WARNING -> - 0x8000
					else -> - 0x10000
				}
				
				holder.tvTime.text = date_fmt.format(time)
				holder.tvMessage.text = message
				holder.tvTime.setTextColor(fg)
				holder.tvMessage.setTextColor(fg)
			}
		}
		
		internal class ViewHolder(root : View) {
			val tvTime : TextView = root.findViewById(R.id.tvTime)
			val tvMessage : TextView = root.findViewById(R.id.tvMessage)
		}
	}
	
	private var last_view_start : Int = 0
	
	private var holder : Holder? = null
	
	internal fun onStart(activity : AppCompatActivity, listView : ListView, loader_id : Int) {
		val holder = Holder(activity, listView)
		this.holder = holder
		holder.onStart(loader_id)
	}
	
	internal fun onStop() {
		val rv = holder?.onStop()
		holder = null
		if(rv != null) last_view_start = rv
	}
	
}
