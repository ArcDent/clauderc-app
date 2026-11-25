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

/** The look picked in 设置 starts at 浮墨, system, default accent, carbon black ink and floating plate nav, and survives restart. */
class ThemeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun startsAtTheDefaultsAndReadsBackWhatWasSet() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val job = Job()
        val first = ThemeStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), produceFile = { file }))

        assertEquals(Appearance(UiStyle.Ink, ThemeMode.System, AccentChoice.Default, PlateInk.Black, PlateNavStyle.Float), first.appearance.first())

        first.setStyle(UiStyle.Plate)
        first.setMode(ThemeMode.Dark)
        first.setAccent(AccentChoice.Green)
        first.setPlateInk(PlateInk.Ultra)
        first.setPlateNav(PlateNavStyle.Dock)
        job.cancelAndJoin()

        // A new data store on the same file, as after the app restarts.
        val second = ThemeStore(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        assertEquals(Appearance(UiStyle.Plate, ThemeMode.Dark, AccentChoice.Green, PlateInk.Ultra, PlateNavStyle.Dock), second.appearance.first())
    }

    /** Legacy style names migrate cleanly: Minimal → 清玻, Grid → 印版, Card / Sticker → 素卡. */
    @Test fun readsTheOldStyleNames() = runTest {
        for ((stored, style) in listOf("Minimal" to UiStyle.Clear, "Grid" to UiStyle.Plate, "Card" to UiStyle.Tile, "Sticker" to UiStyle.Tile)) {
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
