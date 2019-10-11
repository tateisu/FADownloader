package jp.juggler.fadownloader.util

import android.app.Activity
import androidx.viewpager.widget.PagerAdapter
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PagerAdapterBase(val activity : Activity) : PagerAdapter() {
	
	companion object {
		private val log = LogTag("PagerAdapterBase")
	}
	
	private val inflater : LayoutInflater = activity.layoutInflater
	
	private val title_list = ArrayList<CharSequence>()
	private val layout_id_list = ArrayList<Int>()
	val holder_class_list = ArrayList<Class<out PageViewHolder>>()
	val holder_list = SparseArray<PageViewHolder>()
	
	private var loop_mode = false
	
	private val countReal : Int
		get() = title_list.size
	
	abstract class PageViewHolder(val activity : Activity, @Suppress("UNUSED_PARAMETER") ignored : View) {
		
		val is_destroyed = AtomicBoolean(false)
		
//		val isPageDestroyed : Boolean
//			get() = is_destroyed.get() || activity.isFinishing
		
		@Throws(Throwable::class)
		abstract fun onPageCreate(page_idx : Int, root : View)
		
		@Throws(Throwable::class)
		abstract fun onPageDestroy(page_idx : Int, root : View)
	}
	
	fun addPage(
		title : CharSequence,
		layout_id : Int,
		holder_class : Class<out PageViewHolder>
	) : Int {
		val idx = title_list.size
		title_list.add(title)
		layout_id_list.add(layout_id)
		holder_class_list.add(holder_class)
		// ページのインデックスを返す
		return idx
	}
	
	// ページが存在する場合そのViewHolderを返す
	// ページのViewが生成されていない場合はnullを返す
	inline fun <reified T> getPage(idx : Int) : T? {
		val vh = holder_list.get(idx) ?: return null
		return holder_class_list[idx].cast(vh) as T
	}
	
	override fun getCount() : Int {
		return if(loop_mode) Integer.MAX_VALUE else title_list.size
	}
	
	override fun getPageTitle(page_idx : Int) : CharSequence {
		return title_list[page_idx % countReal]
	}
	
	override fun isViewFromObject(view : View, `object` : Any) : Boolean {
		return view === `object`
	}
	
	override fun instantiateItem(container : ViewGroup, page_idx : Int) : Any {
		val root = inflater.inflate(layout_id_list[page_idx % countReal], container, false)
		container.addView(root, 0)
		
		try {
			val holder = holder_class_list[page_idx % countReal]
				.getConstructor(Activity::class.java, View::class.java)
				.newInstance(activity, root)
			//
			holder_list.put(page_idx, holder)
			//
			holder.onPageCreate(page_idx % countReal, root)
			//
		} catch(ex : Throwable) {
			log.trace(ex,"instantiateItem")
		}
		
		return root
	}
	
	override fun destroyItem(container : ViewGroup, page_idx : Int, `object` : Any) {
		val view = `object` as View
		//
		container.removeView(view)
		//
		try {
			val holder = holder_list.get(page_idx)
			holder_list.remove(page_idx)
			if(holder != null) {
				holder.is_destroyed.set(true)
				holder.onPageDestroy(page_idx % countReal, view)
			}
		} catch(ex : Throwable) {
			log.trace(ex,"destroyItem")
		}
		
	}
	
}
