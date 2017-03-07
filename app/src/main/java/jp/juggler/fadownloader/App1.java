package jp.juggler.fadownloader;

import android.app.Application;

public class App1 extends Application{

	@Override public void onCreate(){
		super.onCreate();

		Pref.initialize( getApplicationContext() );
	}
}
