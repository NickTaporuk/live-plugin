package liveplugin.pluginrunner
import com.intellij.openapi.util.io.FileUtil
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static liveplugin.pluginrunner.GroovyPluginRunnerTest.collectErrorsFrom
import static liveplugin.pluginrunner.GroovyPluginRunnerTest.createFile

class ClojurePluginRunnerTest {
	private static final LinkedHashMap NO_BINDING = [:]
	private static final LinkedHashMap NO_ENVIRONMENT = [:]

	private final ErrorReporter errorReporter = new ErrorReporter()
	private final ClojurePluginRunner pluginRunner = new ClojurePluginRunner(errorReporter, NO_ENVIRONMENT)
	private File rootFolder
	private File myPackageFolder


	@Test void "should run correct clojure script without errors"() {
		def scriptCode = """
			; import to ensure that script has access to parent classloader from which test is run
			(import com.intellij.openapi.util.io.FileUtil)

			; some clojure code
			(+ 1 2)
		"""
		createFile("plugin.clj", scriptCode, rootFolder)
		pluginRunner.runPlugin(rootFolder.absolutePath, "someId", NO_BINDING)

		assert collectErrorsFrom(errorReporter).empty
	}

	@Test void "should run incorrect clojure script reporting errors"() {
		def scriptCode = """
			(this is not a proper clojure code)
		"""
		createFile("plugin.clj", scriptCode, rootFolder)
		pluginRunner.runPlugin(rootFolder.absolutePath, "someId", NO_BINDING)

		collectErrorsFrom(errorReporter).with{
			assert size() == 1
			assert first()[0] == "someId"
			assert first()[1].startsWith("java.lang.RuntimeException: Unable to resolve symbol")
		}
	}

	@Ignore @Test void "should run correct clojure script which uses other script"() {
		def scriptCode = """
			(load "util")
			(+ 1 2)
		"""
		def scriptCode2 = """
			(defn foo [] 41)
		"""
		createFile("plugin.clj", scriptCode, rootFolder)
		createFile("util.clj", scriptCode2, myPackageFolder)
		pluginRunner.runPlugin(rootFolder.absolutePath, "someId", NO_BINDING)

		assert collectErrorsFrom(errorReporter).empty
	}

	@Before void setup() {
		rootFolder = FileUtil.createTempDirectory("", "")
		myPackageFolder = new File(rootFolder, "clojure")
		myPackageFolder.mkdir()
	}

	@After void teardown() {
		FileUtil.delete(rootFolder)
	}
}
