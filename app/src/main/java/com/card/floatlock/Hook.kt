package com.card.floatlock

import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * Pins Key Mapper floating buttons to a fixed physical position across rotation.
 *
 * Buttons are Jetpack Compose overlays (no stable label), but Key Mapper lays them out
 * in a STABLE ORDER each pass. PORTRAIT (rotation 0) is the master layout; other
 * orientations are derived by rigid rotation of the matching portrait anchor. Anchors
 * are keyed by order index, matched by nearest position within a pass, and PERSISTED to
 * disk so they survive Key Mapper restarts and reboots (seed once in portrait, done).
 *
 * If a rotation lands mirrored, swap rot==1 and rot==3 in BOTH transform functions.
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET = "io.github.sds100.keymapper"
        private const val OVERLAY_TYPE = 2032
        private const val PASS_GAP_MS = 150L
        private const val MATCH_DIST = 400f
        private const val TAG = "FloatLock"
        private const val FILE = "floatlock_master.csv"

        // each entry: [natX, natY, w, h]
        private val master = ArrayList<FloatArray>()
        private var loaded = false
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

        private fun load(ctx: Context) {
            try {
                val f = File(ctx.filesDir, FILE)
                if (!f.exists()) { log("LOAD none"); return }
                master.clear()
                f.readLines().forEach { line ->
                    val p = line.split(",")
                    if (p.size == 4) {
                        master.add(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                    }
                }
                log("LOAD ${master.size} anchors")
            } catch (t: Throwable) { log("LOAD failed: ${t.message}") }
        }

        private fun save(ctx: Context) {
            try {
                val f = File(ctx.filesDir, FILE)
                f.writeText(master.joinToString("\n") { "${it[0]},${it[1]},${it[2]},${it[3]}" })
            } catch (t: Throwable) { log("SAVE failed: ${t.message}") }
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
        val ctx = view.context?.applicationContext ?: return

        val w = if (view.width > 0) view.width else lp.width.coerceAtLeast(0)
        val h = if (view.height > 0) view.height else lp.height.coerceAtLeast(0)

        val nw: Float
        val nh: Float
        if (rot == 0 || rot == 2) { nw = cw.toFloat(); nh = ch.toFloat() }
        else { nw = ch.toFloat(); nh = cw.toFloat() }

        val kmX = lp.x
        val kmY = lp.y
        val cx = kmX + w / 2f
        val cy = kmY + h / 2f

        synchronized(master) {
            if (!loaded) { load(ctx); loaded = true }

            val now = System.currentTimeMillis()
            if (now - lastCall > PASS_GAP_MS) passIndex = 0
            lastCall = now
            val idx = passIndex
            passIndex++

            if (rot == 0) {
                val nat = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
                val mi = nearestIndex(nat[0], nat[1])
                if (mi >= 0) {
                    master[mi][0] = nat[0]; master[mi][1] = nat[1]
                    master[mi][2] = w.toFloat(); master[mi][3] = h.toFloat()
                } else {
                    master.add(floatArrayOf(nat[0], nat[1], w.toFloat(), h.toFloat()))
                    log("SEED idx=${master.size - 1} x=$kmX y=$kmY w=$w h=$h dim=${cw}x$ch")
                }
                save(ctx)
            } else {
                if (idx < master.size) {
                    val a = master[idx]
                    // size sanity check: warn if this button doesn't match the anchor's size
                    val sizeOk = (w == 0 || a[2] == 0f || (w.toFloat() == a[2] && h.toFloat() == a[3]))
                    val cur = naturalToCurrent(a[0], a[1], rot, nw, nh)
                    lp.x = (cur[0] - w / 2f).toInt()
                    lp.y = (cur[1] - h / 2f).toInt()
                    if (!sizeOk) {
                        log("FORCE idx=$idx rot=$rot SIZE-MISMATCH view=${w}x$h anchor=${a[2].toInt()}x${a[3].toInt()} -> (${lp.x},${lp.y})")
                    } else {
                        log("FORCE idx=$idx rot=$rot km=($kmX,$kmY) -> (${lp.x},${lp.y})")
                    }
                } else {
                    log("NO-ANCHOR idx=$idx rot=$rot (seed portrait first)")
                }
            }
        }
    }
}
