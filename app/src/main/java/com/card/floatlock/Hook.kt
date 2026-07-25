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
 * Key Mapper's buttons are Jetpack Compose overlays with no stable label to key on,
 * but Key Mapper updates them in a STABLE ORDER every layout pass (verified from logs).
 * So:
 *   - PORTRAIT (rotation 0) is the master layout. Portrait positions are stored as an
 *     ordered list of anchors (natural frame). Re-seen portrait buttons are matched to
 *     their nearest anchor, so dragging in portrait updates the anchor.
 *   - Any other orientation is DERIVED: the i-th button in the pass is forced to the
 *     rigid rotation of the i-th portrait anchor. Key Mapper's own (stretched)
 *     landscape positions are ignored entirely.
 *
 * If a rotation lands mirrored, swap the rot==1 and rot==3 branches in BOTH transforms.
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET = "io.github.sds100.keymapper"
        private const val OVERLAY_TYPE = 2032          // TYPE_ACCESSIBILITY_OVERLAY
        private const val PASS_GAP_MS = 150L           // gap that starts a new layout pass
        private const val MATCH_DIST = 400f            // px radius to match a portrait anchor
        private const val TAG = "FloatLock"

        private val master = ArrayList<FloatArray>()   // ordered portrait anchors (natural center)
        private var passIndex = 0
        private var lastCall = 0L

        private fun log(m: String) = XposedBridge.log("$TAG: $m")

        private fun naturalToCurrent(nx: Float, ny: Float, rot: Int, nw: Float, nh: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(ny, nw - nx)
                2 -> floatArrayOf(nw - nx, nh - ny)
                3 -> floatArrayOf(nh - ny, nx)
                else -> floatArrayOf(nx, ny)
            }

        private fun currentToNatural(x: Float, y: Float, rot: Int, cw: Float, ch: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(ch - y, x)
                2 -> floatArrayOf(cw - x, ch - y)
                3 -> floatArrayOf(y, cw - x)
                else -> floatArrayOf(x, y)
            }

        private fun nearestIndex(cx: Float, cy: Float): Int {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in master.indices) {
                val dx = master[i][0] - cx
                val dy = master[i][1] - cy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
            }
            return if (best >= 0 && bestD <= MATCH_DIST * MATCH_DIST) best else -1
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

        val nw: Float
        val nh: Float
        if (rot == 0 || rot == 2) { nw = cw.toFloat(); nh = ch.toFloat() }
        else { nw = ch.toFloat(); nh = cw.toFloat() }

        val kmX = lp.x
        val kmY = lp.y
        val cx = kmX + w / 2f
        val cy = kmY + h / 2f

        val now = System.currentTimeMillis()
        if (now - lastCall > PASS_GAP_MS) passIndex = 0
        lastCall = now
        val idx = passIndex
        passIndex++

        if (rot == 0) {
            // Portrait = master layout. Match to nearest anchor (drag) or append (seed).
            val nat = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            val mi = nearestIndex(nat[0], nat[1])
            if (mi >= 0) {
                master[mi][0] = nat[0]; master[mi][1] = nat[1]
            } else {
                master.add(floatArrayOf(nat[0], nat[1]))
                log("SEED idx=${master.size - 1} x=$kmX y=$kmY w=$w h=$h dim=${cw}x$ch")
            }
        } else {
            // Derived orientation -> force i-th button to rigid rotation of i-th anchor.
            if (idx < master.size) {
                val a = master[idx]
                val cur = naturalToCurrent(a[0], a[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
                log("FORCE idx=$idx rot=$rot km=($kmX,$kmY) -> (${lp.x},${lp.y})")
            } else {
                log("NO-ANCHOR idx=$idx rot=$rot (show buttons in portrait first)")
            }
        }
    }
}
