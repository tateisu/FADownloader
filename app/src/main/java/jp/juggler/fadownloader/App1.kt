package jp.juggler.fadownloader

import android.app.Application

class App1 : Application() {
	
	override fun onCreate() {
		super.onCreate()
		
		Pref.initialize(applicationContext)
	}
}
