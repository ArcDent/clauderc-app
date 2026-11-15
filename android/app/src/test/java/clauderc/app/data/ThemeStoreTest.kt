package clauderc.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The look picked in 设置 starts at minimal, system, blue and survives a fresh store on the same file. */
class ThemeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun startsAtTheDefaultsAndReadsBackWhatWasSet() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val job = Job()
        val first = ThemeStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), produceFile = { file }))

        assertEquals(Appearance(UiStyle.Minimal, ThemeMode.System, AccentChoice.Blue), first.appearance.first())

        first.setStyle(UiStyle.Card)
        first.setMode(ThemeMode.Dark)
        first.setAccent(AccentChoice.Green)
        job.cancelAndJoin()

        // A new data store on the same file, as after the app restarts.
        val second = ThemeStore(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        assertEquals(Appearance(UiStyle.Card, ThemeMode.Dark, AccentChoice.Green), second.appearance.first())
    }
}
