package com.relavoi.sdk.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.relavoi.sdk.R
import com.relavoi.sdk.internal.Logger
import kotlin.math.roundToInt

/**
 * Floating-bubble overlay shown above other apps when a Relavoi call is in progress.
 * Green ring for verified, red ring for unverified. Drag to move. Auto-dismisses after
 * 30 seconds so it never gets stuck on the user's screen.
 *
 * Host apps shouldn't start this directly — use [show] / [hide].
 */
class FloatingBubbleService : Service() {

    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        Logger.d("FloatingBubbleService auto-dismissing after ${AUTO_DISMISS_MS}ms")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verified = intent?.getBooleanExtra(EXTRA_VERIFIED, false) ?: false
        try {
            showBubble(verified)
            mainHandler.removeCallbacks(autoDismissRunnable)
            mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
        } catch (t: Throwable) {
            Logger.w("FloatingBubbleService.show failed: ${t.message}", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showBubble(verified: Boolean) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Tear down any previous view in case of re-show.
        removeBubble(wm)

        val drawableRes = if (verified) R.drawable.bubble_verified else R.drawable.bubble_unverified
        val sizePx = dp(56)
        val container = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setBackgroundResource(drawableRes)
        }
        bubbleView = container

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(24)
            y = dp(120)
        }
        layoutParams = lp

        attachDragHandler(container, wm, lp)
        wm.addView(container, lp)
        Logger.d("FloatingBubbleService bubble shown (verified=$verified)")
    }

    private fun attachDragHandler(view: View, wm: WindowManager, lp: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Note: gravity is TOP|END so x grows leftward. Negate dx to feel natural.
                    lp.x = (initialX - (event.rawX - initialTouchX)).toInt().coerceAtLeast(0)
                    lp.y = (initialY + (event.rawY - initialTouchY)).toInt().coerceAtLeast(0)
                    try {
                        wm.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                        Logger.w("updateViewLayout failed: ${t.message}")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble(wm: WindowManager) {
        bubbleView?.let {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
                Logger.w("removeView failed: ${t.message}")
            }
        }
        bubbleView = null
        layoutParams = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            removeBubble(wm)
        } catch (t: Throwable) {
            Logger.w("FloatingBubbleService.onDestroy cleanup failed: ${t.message}")
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * Resources.getSystem().displayMetrics.density).roundToInt()

    companion object {
        private const val EXTRA_VERIFIED = "verified"
        private const val AUTO_DISMISS_MS = 30_000L

        /**
         * Start (or restart) the floating bubble. Caller is responsible for ensuring
         * the host has been granted [android.Manifest.permission.SYSTEM_ALERT_WINDOW]
         * via [android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION].
         */
        @JvmStatic
        fun show(context: Context, verified: Boolean) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra(EXTRA_VERIFIED, verified)
            }
            context.startService(intent)
        }

        /** Stop the floating bubble. */
        @JvmStatic
        fun hide(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
