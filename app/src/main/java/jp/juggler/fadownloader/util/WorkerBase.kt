package jp.juggler.fadownloader.util

import jp.juggler.fadownloader.CancelChecker
import java.util.concurrent.atomic.AtomicReference

abstract class WorkerBase : Thread(), CancelChecker {
	
	internal val cancel_reason = AtomicReference<String>(null)
	
	override val isCancelled : Boolean
		get() = cancel_reason.get() != null
	
	@Synchronized
	fun waitEx(ms : Long) {
		try {
			WaitHelper.wait(this,ms)
		} catch(ignored : InterruptedException) {
		}
		
	}
	
	@Synchronized
	fun notifyEx() {
		WaitHelper.notify(this)
	}
	
	open fun cancel(reason : String) : Boolean {
		val rv = cancel_reason.compareAndSet(null, reason)
		notifyEx()
		return rv
	}
	
	abstract override fun run()
}
