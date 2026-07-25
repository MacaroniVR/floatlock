package com.card.floatlock

import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Locks Key Mapper's floating buttons to a fixed PHYSICAL position across rotation.
 *
 * Key Mapper draws each floating button as an accessibility overlay
 * (WindowManager.LayoutParams.type == 2032) positioned by absolute x/y, and it
 * recomputes those x/y on rotation (which is what moves the buttons). We hook the
 * stable Android calls WindowManagerImpl.addView / updateViewLayout, and for those
 * overlay windows we override x/y so each button's CENTER stays at the same physical
 * point regardless of the current display rotation.
 *
 * Model: each overlay's position is stored as a center point in the device's NATURAL
 * (rotation-0) coordinate frame. On a rotation we recompute the current-orientation
 * x/y from that stored center. While the rotation is unchanged (and after a short
 * settle window) we treat position changes as user drags and update the stored center.
 *
 * The 90 vs 270 physical direction is a convention that may need flipping for a given
 * device; if a rotation lands mirrored, swap the rot==1 and rot==3 branches in BOTH
 * transform functions.
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET = "io.github.sds100.keymapper"
        private const val OVERLAY_TYPE = 2032          // TYPE_ACCESSIBILITY_OVERLAY
        private const val SETTLE_MS = 600L
        private const val TAG = "FloatLock"

        // keyed by System.identityHashCode(view) to avoid holding View refs
        private val anchorCenter = HashMap<Int, FloatArray>()   // natural-frame (cx, cy)
        private val lastRot = HashMap<Int, Int>()
        private var lastRotChange = 0L

        private fun log(m: String) = XposedBridge.log("$TAG: $m")

        // natural-frame center -> current display coords (NW,NH = natural dims)
        private fun naturalToCurrent(nx: Float, ny: Float, rot: Int, nw: Float, nh: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(nh - ny, nx)          // ROTATION_90
                2 -> floatArrayOf(nw - nx, nh - ny)     // ROTATION_180
                3 -> floatArrayOf(ny, nw - nx)          // ROTATION_270
                else -> floatArrayOf(nx, ny)            // ROTATION_0
            }

        // current display coords -> natural-frame (cw,ch = CURRENT dims)
        private fun currentToNatural(x: Float, y: Float, rot: Int, cw: Float, ch: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(y, cw - x)            // inverse of 90
                2 -> floatArrayOf(cw - x, ch - y)       // inverse of 180
                3 -> floatArrayOf(ch - y, x)            // inverse of 270
                else -> floatArrayOf(x, y)
            }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET) return

        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.args[0] as? View ?: return
                    val lp = param.args[1] as? WindowManager.LayoutParams ?: return
                    if (lp.type != OVERLAY_TYPE) return
                    handle(view, lp)
                } catch (t: Throwable) {
                    // Never crash Key Mapper because of us.
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", lpparam.classLoader,
                "updateViewLayout", View::class.java, ViewGroup.LayoutParams::class.java, cb
            )
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", lpparam.classLoader,
                "addView", View::class.java, ViewGroup.LayoutParams::class.java, cb
            )
            log("Hooks installed in $TARGET")
        } catch (t: Throwable) {
            log("Failed to install hooks: ${t.message}")
        }
    }

    private fun handle(view: View, lp: WindowManager.LayoutParams) {
        val display = view.display ?: return
        val rot = display.rotation
        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        val cw = size.x
        val ch = size.y
        if (cw <= 0 || ch <= 0) return

        val w = if (view.width > 0) view.width else 0
        val h = if (view.height > 0) view.height else 0
        val key = System.identityHashCode(view)

        // Natural (rotation-0) dimensions are rotation-invariant.
        val nw: Float
        val nh: Float
        if (rot == 0 || rot == 2) { nw = cw.toFloat(); nh = ch.toFloat() }
        else { nw = ch.toFloat(); nh = cw.toFloat() }

        val cx = lp.x + w / 2f
        val cy = lp.y + h / 2f
        val now = System.currentTimeMillis()

        if (!anchorCenter.containsKey(key)) {
            // First time we see this overlay: adopt its current position as the anchor.
            anchorCenter[key] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            lastRot[key] = rot
            log("SEED key=$key rot=$rot x=${lp.x} y=${lp.y} w=$w h=$h dim=${cw}x$ch")
            return
        }

        val prev = lastRot[key] ?: rot
        when {
            rot != prev -> {
                // Rotation changed -> hold the physical center.
                lastRotChange = now
                lastRot[key] = rot
                val c = anchorCenter[key]!!
                val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
                log("LOCK(rot->$rot) key=$key set x=${lp.x} y=${lp.y}")
            }
            now - lastRotChange < SETTLE_MS -> {
                // Settling right after a rotation: keep holding, ignore transient updates.
                val c = anchorCenter[key]!!
                val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
            }
            else -> {
                // Same rotation, settled -> user drag; update the stored physical center.
                anchorCenter[key] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            }
        }
    }
}
