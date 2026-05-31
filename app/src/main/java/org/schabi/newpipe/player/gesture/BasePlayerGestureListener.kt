package org.schabi.newpipe.player.gesture

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.os.postDelayed
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.ui.VideoPlayerUi

/**
 * Base gesture handling for [Player]
 *
 * This class contains the logic for the player gestures like View preparations
 * and provides some abstract methods to make it easier separating the logic from the UI.
 */
abstract class BasePlayerGestureListener(
    private val playerUi: VideoPlayerUi
) : GestureDetector.SimpleOnGestureListener(), View.OnTouchListener {

    protected val player: Player = playerUi.player
    protected val binding: PlayerBinding = playerUi.binding

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        playerUi.gestureDetector.onTouchEvent(event)
        return false
    }

    private fun onDoubleTap(
        event: MotionEvent,
        portion: DisplayPortion
    ) {
        if (DEBUG) {
            Log.d(
                TAG,
                "onDoubleTap called with playerType = [" +
                    player.playerType + "], portion = [" + portion + "]"
            )
        }
        if (playerUi.isSomePopupMenuVisible) {
            playerUi.hideControls(0, 0)
        }
        if (portion === DisplayPortion.LEFT || portion === DisplayPortion.RIGHT) {
            // While playing, hide the controls overlay (title, timeline, ...) instantly so it
            // doesn't linger over the seek. While paused we keep it shown on purpose.
            if (player.playWhenReady) {
                playerUi.hideControls(0, 0)
            }
            startMultiDoubleTap(event)
        } else if (portion === DisplayPortion.MIDDLE) {
            player.playPause()
        }
    }

    protected fun onSingleTap() {
        if (playerUi.isControlsVisible) {
            playerUi.hideControls(150, 0)
            return
        }
        // -- Controls are not visible --

        // When player is completed show controls and don't hide them later
        if (player.currentState == Player.STATE_COMPLETED) {
            playerUi.showControls(0)
        } else {
            playerUi.showControlsThenHide()
        }
    }

    open fun onScrollEnd(event: MotionEvent) {
        if (DEBUG) {
            Log.d(
                TAG,
                "onScrollEnd called with playerType = [" +
                    player.playerType + "]"
            )
        }
        if (playerUi.isControlsVisible && player.currentState == Player.STATE_PLAYING) {
            playerUi.hideControls(
                VideoPlayerUi.DEFAULT_CONTROLS_DURATION,
                VideoPlayerUi.DEFAULT_CONTROLS_HIDE_TIME
            )
        }
    }

    // ///////////////////////////////////////////////////////////////////
    // Simple gestures
    // ///////////////////////////////////////////////////////////////////

    override fun onDown(e: MotionEvent): Boolean {
        if (DEBUG) {
            Log.d(TAG, "onDown called with e = [$e]")
        }

        if (isDoubleTapping && isDoubleTapEnabled) {
            doubleTapControls?.onDoubleTapProgressDown(getDisplayPortion(e))
            return true
        }

        if (onDownNotDoubleTapping(e)) {
            return super.onDown(e)
        }
        return true
    }

    /**
     * @return true if `super.onDown(e)` should be called, false otherwise
     */
    open fun onDownNotDoubleTapping(e: MotionEvent): Boolean {
        return false // do not call super.onDown(e) by default, overridden for popup player
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (DEBUG) {
            Log.d(TAG, "onDoubleTap called with e = [$e]")
        }

        // A fast double tap was recognized by the framework, so drop any pending slow-tap state
        // to avoid a following single tap being paired with it.
        lastTapPortion = null
        onDoubleTap(e, getDisplayPortion(e))
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        // The framework's GestureDetector only treats two taps as a double tap when they happen
        // within ViewConfiguration.getDoubleTapTimeout() (~300ms, not configurable), which is too
        // strict for comfortable double-tap seeking. To make it a bit more forgiving we recognize
        // a slightly slower second tap on the same side here and convert it into a double tap.
        if (isDoubleTapping) {
            // Already in a seek session; further taps are handled as continuation via onDown().
            return true
        }

        val portion = getDisplayPortion(e)
        val withinWindow = e.eventTime - lastTapUpTime <= DOUBLE_TAP_DETECT_WINDOW
        if (lastTapPortion == portion && withinWindow) {
            lastTapPortion = null
            onDoubleTap(e, portion)
            if (portion == DisplayPortion.LEFT || portion == DisplayPortion.RIGHT) {
                // The framework path performs the first seek from onDown() once isDoubleTapping is
                // set. Since our mode switch happens here on tap up (after onDown() already ran),
                // trigger that first seek explicitly. (onDoubleTap above already took care of
                // hiding the controls overlay when playing.)
                doubleTapControls?.onDoubleTapProgressDown(portion)
            }
            return true
        }

        lastTapPortion = portion
        lastTapUpTime = e.eventTime
        return true
    }

    // ///////////////////////////////////////////////////////////////////
    // Multi double tapping
    // ///////////////////////////////////////////////////////////////////

    private var doubleTapControls: DoubleTapListener? = null

    private val isDoubleTapEnabled: Boolean
        get() = doubleTapDelay > 0

    var isDoubleTapping = false
        private set

    fun doubleTapControls(listener: DoubleTapListener) = apply {
        doubleTapControls = listener
    }

    private var doubleTapDelay = DOUBLE_TAP_DELAY
    private val doubleTapHandler: Handler = Handler(Looper.getMainLooper())

    // Tracks the previous discrete tap so a slightly-too-slow second tap can still be recognized
    // as a double tap (see onSingleTapUp), widening the framework's strict ~300ms window.
    private var lastTapPortion: DisplayPortion? = null
    private var lastTapUpTime = 0L

    private fun startMultiDoubleTap(e: MotionEvent) {
        if (!isDoubleTapping) {
            if (DEBUG) {
                Log.d(TAG, "startMultiDoubleTap called with e = [$e]")
            }

            keepInDoubleTapMode()
            doubleTapControls?.onDoubleTapStarted(getDisplayPortion(e))
        }
    }

    fun keepInDoubleTapMode() {
        if (DEBUG) {
            Log.d(TAG, "keepInDoubleTapMode called")
        }

        isDoubleTapping = true
        doubleTapHandler.removeCallbacksAndMessages(DOUBLE_TAP)
        doubleTapHandler.postDelayed(DOUBLE_TAP_DELAY, DOUBLE_TAP) {
            if (DEBUG) {
                Log.d(TAG, "doubleTapRunnable called")
            }

            isDoubleTapping = false
            doubleTapControls?.onDoubleTapFinished()
        }
    }

    fun endMultiDoubleTap() {
        if (DEBUG) {
            Log.d(TAG, "endMultiDoubleTap called")
        }

        isDoubleTapping = false
        doubleTapHandler.removeCallbacksAndMessages(DOUBLE_TAP)
        doubleTapControls?.onDoubleTapFinished()
    }

    // ///////////////////////////////////////////////////////////////////
    // Utils
    // ///////////////////////////////////////////////////////////////////

    abstract fun getDisplayPortion(e: MotionEvent): DisplayPortion

    // Currently needed for scrolling since there is no action more the middle portion
    abstract fun getDisplayHalfPortion(e: MotionEvent): DisplayPortion

    companion object {
        private const val TAG = "BasePlayerGestListener"
        private val DEBUG = Player.DEBUG

        private const val DOUBLE_TAP = "doubleTap"
        private const val DOUBLE_TAP_DELAY = 550L

        // Max time between two taps for our fallback detector to still treat them as a double tap.
        // Wider than the framework's non-configurable ~300ms timeout to make seeking less fiddly.
        private const val DOUBLE_TAP_DETECT_WINDOW = 500L
    }
}
