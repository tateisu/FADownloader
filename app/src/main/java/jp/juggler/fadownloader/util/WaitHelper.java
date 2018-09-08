package jp.juggler.fadownloader.util;

import org.jetbrains.annotations.NotNull;

public class WaitHelper {
	public static void wait( @NotNull Object o, long ms ) throws InterruptedException{
		o.wait(ms);
	}
	
	public static void notify( @NotNull Object o ){
		o.notify();
	}
}
