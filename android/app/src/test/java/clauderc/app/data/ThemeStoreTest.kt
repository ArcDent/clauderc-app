package clauderc.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** The look picked in 设置 starts at 清玻, system, blue and survives a fresh store on the same file. */
class ThemeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun startsAtTheDefaultsAndReadsBackWhatWasSet() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val job = Job()
        val first = ThemeStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), produceFile = { file }))

        assertEquals(Appearance(UiStyle.Clear, ThemeMode.System, AccentChoice.Blue), first.appearance.first())

        first.setStyle(UiStyle.Grid)
        first.setMode(ThemeMode.Dark)
        first.setAccent(AccentChoice.Green)
        job.cancelAndJoin()

        // A new data store on the same file, as after the app restarts.
        val second = ThemeStore(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        assertEquals(Appearance(UiStyle.Grid, ThemeMode.Dark, AccentChoice.Green), second.appearance.first())
    }

    /** Phones that picked a style before 清玻 / 格栅 / 贴纸 keep the closest one: 极简 is 清玻, 卡片 is 贴纸. */
    @Test fun readsTheOldStyleNames() = runTest {
        for ((stored, style) in listOf("Minimal" to UiStyle.Clear, "Card" to UiStyle.Sticker)) {
            val file = File(tmp.root, "old-$stored.preferences_pb")
            val job = Job()
            val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), produceFile = { file })
            raw.edit { it[stringPreferencesKey("appearance|style")] = stored }
            job.cancelAndJoin()

            val store = ThemeStore(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
            assertEquals(stored, style, store.appearance.first().style)
        }
    }
}
