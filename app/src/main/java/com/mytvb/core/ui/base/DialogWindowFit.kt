package com.mytvb.core.ui.base

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager

/**
 * 浮层弹窗尺寸钳制：布局按设计 px 池尺寸写死时（如 dialog_owner_detail 的
 * px1400×px935），在低分屏/大界面缩放系数下换算出的像素可能超过屏幕物理尺寸，
 * 浮层 window 会被系统钳到屏幕大小而内容仍按原尺寸绘制，边缘内容被裁。
 * 统一在「设计尺寸」与「屏幕 92%」之间取小值。
 *
 * 高度传 0 表示按内容自适应（WRAP_CONTENT）。
 */
object DialogWindowFit {

    fun apply(
        window: Window?,
        context: Context,
        designWidthPx: Int,
        designHeightPx: Int = 0
    ) {
        val w = window ?: return
        val dm = context.resources.displayMetrics
        val width = minOf(designWidthPx, (dm.widthPixels * 0.92f).toInt())
        val maxHeight = (dm.heightPixels * 0.92f).toInt()
        val height = if (designHeightPx <= 0) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            minOf(designHeightPx, maxHeight)
        }
        w.setLayout(width, height)
        if (designHeightPx <= 0) followContentHeight(w, context, width, maxHeight)
    }

    /**
     * [btr-hardening] Android 9 的 ViewRootImpl 把 wrap_content 視窗的高度上限算成
     * dipToPx(screenHeightDp)，用的是被 UiScale 改過的 density（2.0 → 1.0），
     * 1080p 上限只剩 540px，內容較長的對話框底部按鈕被壓扁。上限比螢幕小時，
     * 每次排版後自己量內容高度，直接給視窗固定高度（內容變動時跟著更新）。
     */
    private fun followContentHeight(window: Window, context: Context, width: Int, maxHeight: Int) {
        val res = context.resources
        val systemCap = (res.configuration.screenHeightDp * res.displayMetrics.density).toInt()
        if (systemCap >= maxHeight) return
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
            val wanted = decor.measuredHeight
            if (wanted > 0 && wanted != window.attributes.height) {
                window.setLayout(width, wanted)
            }
        })
    }
}
