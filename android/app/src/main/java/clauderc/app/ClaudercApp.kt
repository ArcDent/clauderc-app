package clauderc.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import clauderc.app.net.ForegroundBinder

class ClaudercApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Connect while any screen is visible, disconnect in the background (design D10).
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundBinder(container.connection))
    }
}
