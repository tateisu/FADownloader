package jp.juggler.fadownloader;

import java.util.concurrent.atomic.AtomicReference;

abstract public class WorkerBase extends Thread implements CancelChecker{

	public synchronized void waitEx( long ms ){
		try{
			wait( ms );
		}catch( InterruptedException ignored ){
		}
	}

	public synchronized void notifyEx(){
		notify();
	}

	final AtomicReference<String> cancel_reason = new AtomicReference<>( null );

	@Override public boolean isCancelled(){
		return cancel_reason.get() != null;
	}

	public boolean cancel(String reason){
		boolean rv = cancel_reason.compareAndSet( null, reason );
		notifyEx();
		return rv;
	}

	public abstract void run();
}
