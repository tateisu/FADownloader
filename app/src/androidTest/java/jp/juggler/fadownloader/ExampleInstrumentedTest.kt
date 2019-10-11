package jp.juggler.fadownloader

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
