package com.card.floatlock

import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Pins Key Mapper floating buttons to a fixed physical position across rotation.
 *
 * Buttons are keyed by a STABLE identifier (label text / content description) so the
 * lock survives Key Mapper recreating the overlays (e.g. when a fullscreen landscape
 * game launches). PORTRAIT (rotation 0) is the source of truth: you place buttons in
 * portrait, and other orientations are derived by rigid rotation of that anchor.
 *
 * The first time each button is seen, its full view structure is logged so we can
 * confirm which stable identifier is available.
 */
class Hook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET = "io.github.sds100.keymapper"
        private const val OVERLAY_TYPE = 2032          // TYPE_ACCESSIBILITY_OVERLAY
        private const val SETTLE_MS = 700L
        private const val TAG = "FloatLock"

        // keyed by stable button id (text / contentDescription)
        private val anchorCenter = HashMap<String, FloatArray>()  // natural-frame (cx, cy)
        private val anchorRot = HashMap<String, Int>()            // rotation the anchor was set in
        private val dumped = HashSet<String>()
        private var lastRotChange = 0L

        private fun log(m: String) = XposedBridge.log("$TAG: $m")

        // natural-frame center -> current display coords (direction matched to device)
        private fun naturalToCurrent(nx: Float, ny: Float, rot: Int, nw: Float, nh: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(ny, nw - nx)
                2 -> floatArrayOf(nw - nx, nh - ny)
                3 -> floatArrayOf(nh - ny, nx)
                else -> floatArrayOf(nx, ny)
            }

        // current display coords -> natural-frame (cw,ch = CURRENT dims)
        private fun currentToNatural(x: Float, y: Float, rot: Int, cw: Float, ch: Float): FloatArray =
            when (rot) {
                1 -> floatArrayOf(ch - y, x)
                2 -> floatArrayOf(cw - x, ch - y)
                3 -> floatArrayOf(y, cw - x)
                else -> floatArrayOf(x, y)
            }

        private fun findText(v: View): String? {
            if (v is TextView) {
                val s = v.text?.toString()
                if (!s.isNullOrBlank()) return s
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    findText(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }

        private fun buttonId(view: View): String {
            view.contentDescription?.toString()?.let { if (it.isNotBlank()) return "cd:$it" }
            findText(view)?.let { return "tx:$it" }
            return "sz:${view.width}x${view.height}"
        }

        private fun dump(v: View, depth: Int, sb: StringBuilder) {
            val pad = "  ".repeat(depth)
            val cls = v.javaClass.simpleName
            val cd = v.contentDescription
            val txt = if (v is TextView) v.text else null
            sb.append("$pad$cls cd=$cd txt=$txt\n")
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1, sb)
            }
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

        val id = buttonId(view)

        // One-time structure dump per button, to confirm the stable identifier.
        if (dumped.add(id)) {
            val sb = StringBuilder("DUMP id=$id class=${view.javaClass.name}\n")
            dump(view, 1, sb)
            log(sb.toString().trimEnd())
        }

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

        val known = anchorCenter.containsKey(id)
        if (!known) {
            anchorCenter[id] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            anchorRot[id] = rot
            log("SEED id=$id rot=$rot x=$kmX y=$kmY w=$w h=$h dim=${cw}x$ch")
            return
        }

        val aRot = anchorRot[id] ?: rot
        if (rot == aRot) {
            // In the anchor orientation. After the settle window, treat as a user drag
            // and update the physical anchor; during settle, hold it.
            if (now - lastRotChange > SETTLE_MS) {
                anchorCenter[id] = currentToNatural(cx, cy, rot, cw.toFloat(), ch.toFloat())
            } else {
                val c = anchorCenter[id]!!
                val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
                lp.x = (cur[0] - w / 2f).toInt()
                lp.y = (cur[1] - h / 2f).toInt()
            }
        } else {
            // Derived orientation -> force to the rigid rotation of the anchor.
            lastRotChange = now
            val c = anchorCenter[id]!!
            val cur = naturalToCurrent(c[0], c[1], rot, nw, nh)
            lp.x = (cur[0] - w / 2f).toInt()
            lp.y = (cur[1] - h / 2f).toInt()
            log("FORCE id=$id rot=$rot km=($kmX,$kmY) -> (${lp.x},${lp.y})")
        }
    }
}
