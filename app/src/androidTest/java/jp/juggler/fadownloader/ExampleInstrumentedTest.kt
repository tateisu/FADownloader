package jp.juggler.fadownloader

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Android instrumentation test は run configuration を編集しないと Empty tests とかいうエラーになります

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
	
	@Test
	@Throws(Exception::class)
	fun useAppContext() {
		// Context of the app under test.
		val appContext = InstrumentationRegistry.getTargetContext()
		
		assertEquals("jp.juggler.fadownloader", appContext.getPackageName())
	}
}
