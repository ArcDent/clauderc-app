package clauderc.app.testing

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The magnifier a text selection shows while its handle is dragged, as a no-op: Robolectric's has no
 * surface, so dismissing the real one throws. For tests that select text.
 */
@Implements(Magnifier::class)
class ShadowMagnifier {
    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun dismiss() = Unit

    @Implementation
    fun update() = Unit
}
