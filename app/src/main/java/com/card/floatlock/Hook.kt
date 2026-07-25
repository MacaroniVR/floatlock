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
 * Pins Key Mapper floating buttons to a fixed physical position across rotation.
 *
 * DIAGNOSTIC BUILD: when DIAG=true, in landscape (rot=1) every overlay is slammed to
 * the top-left corner (100,100) and portrait is left untouched, to prove whether our
 * position writes actually render. Incoming (Key Mapper) coords are logged next to
 * what we set. Set DIAG=false to restore the real locking logic below.
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET = "io.github.sds100.keymapper"
        private const val OVERLAY_TYPE = 2032          // TYPE_ACCESSIBILITY_OVERLAY
        private const val SETTLE_MS = 600L
        private const val TAG = "FloatLock"
        private const val DIAG = true                  // <-- diagnostic slam mode

        private val anchorCenter = HashMap<Int, FloatArray>()
        private val lastRot = HashMap<Int, Int>()
        private var lastRotChange = 0L

        private fun log(m: String) = XposedBridge.log("$TAG: $m")

        private fun naturalToCurrent(nx: Float, ny: Float, rot: Int, nw: Float, nh: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(nh - ny, nx)
                2 -> floatArrayOf(nw - nx, nh - ny)
                3 -> floatArrayOf(ny, nw - nx)
                else -> floatArrayOf(nx, ny)
            }

        private fun currentToNatural(x: Float, y: Float, rot: Int, cw: Float, ch: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(y, cw - x)
                2 -> floatArrayOf(cw - x, ch - y)
                3 -> floatArrayOf(ch - y, x)
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
            log("Hooks installed in $TARGET (DIAG=$DIAG)")
        } catch (t: Throwable) {
            log("Failed to install hooks: ${t.message}")
        }
    }

    private fun handle(view: View, lp: WindowManager.LayoutParams) {
        val display = view.display ?: return
        val rot = display.rotation
        val kmX = lp.x
        val kmY = lp.y
        val key = System.identityHashCode(view)

        if (DIAG) {
            if (rot == 1) {
                lp.x = 100
                lp.y = 100
                log("SLAM key=$key rot=1 in=($kmX,$kmY) out=(100,100)")
            } else {
                log("PASS key=$key rot=$rot in=($kmX,$kmY)")
            }
            return
        }

        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        val cw = size.x
        val ch = size.y
        if (cw <= 0 || ch <= 0) return

        val w = if (view.width > 0) view.width else 0
        val h = if (view.height > 0) view.height else 0

        val nw: Float
        val nh: Float
        if (rot == 0 || rot == 2) { nw = cw.toFloat(); nh = ch.toFloat() }
        else { nw = ch.toFloat(); nh = cw.toFloat() }

        val cx = lp.x + w / 2f
        val cy = lp.y + h / 2f
        val now = System.currentTimeMillis()

        if (!anchorCenter.containsKey(key)) {
            anchorCenter[key] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            lastRot[key] = rot
            return
        }

        val prev = lastRot[key] ?: rot
        when {
            rot != prev -> {
                lastRotChange = now
                lastRot[key] = rot
                val c = anchorCenter[key]!!
                val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
            }
            now - lastRotChange < SETTLE_MS -> {
                val c = anchorCenter[key]!!
                val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
            }
            else -> {
                anchorCenter[key] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            }
        }
    }
}
