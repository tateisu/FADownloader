package jp.juggler.fadownloader.util

import java.util.concurrent.atomic.AtomicReference

class AtomicReferenceNotNull<V : Any>(value : V) {
	
	private val ar = AtomicReference<V>(value)
	
	fun get() : V = requireNotNull(ar.get())
	fun set(value : V) = ar.set(value)
	fun lazySet(value : V) = ar.lazySet(value)
	fun compareAndSet(expect : V, update : V) = ar.compareAndSet(expect, update)
	fun weakCompareAndSet(expect : V, update : V) = ar.weakCompareAndSet(expect, update)
	fun getAndSet(newValue : V) : V = requireNotNull(ar.getAndSet(newValue))
	override fun toString() : String {
		return get().toString()
	}
}