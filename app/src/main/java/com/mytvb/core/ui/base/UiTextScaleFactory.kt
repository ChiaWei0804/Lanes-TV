package com.mytvb.core.ui.base

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatViewInflater

/**
 * UI 文字缩放工厂：把 XML inflate 出来的 TextView 替换为 [ScaledTextView]，
 * 其余类名委托 [AppCompatViewInflater]（保持 AppCompat 组件转换行为），
 * 全限定类名返回 null 走系统默认机制。
 *
 * 必须在 Activity 的 super.onCreate 之前 [install]——此后 AppCompat 检测到已有
 * Factory 会跳过自装，AppCompat 组件转换由本工厂的委托补齐。
 */
object UiTextScaleFactory {

    private const val APPCOMPAT_TEXT_VIEW =
        "androidx.appcompat.widget.AppCompatTextView"

    private val viewInflater = ExposedAppCompatViewInflater()

    /**
     * AppCompatViewInflater.createView 是 protected（供 Material 主题子类扩展），
     * 用空子类公开它，以保持短名组件（ImageView/Button 等）的 AppCompat 转换行为。
     */
    private class ExposedAppCompatViewInflater : AppCompatViewInflater() {
        public override fun createView(context: Context, name: String, attrs: AttributeSet): View? {
            return super.createView(context, name, attrs)
        }
    }

    fun install(inflater: LayoutInflater) {
        if (inflater.factory2 != null) return
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? = onCreateView(name, context, attrs)

            override fun onCreateView(
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? = createView(context, attrs, name)
        }
    }

    private fun createView(context: Context, attrs: AttributeSet, name: String): View? {
        // [btr-hardening] 每個 View 建立前檢查 density：播放器關閉後系統會把 density
        // 重設成電視原生值（實測 1.0 → 2.0），之後才建立的 View 會整個放大。
        UiScale.ensure(context.resources, "inflate:$name")
        UiScale.ensure(context.applicationContext.resources, "application")
        if (name == "TextView" || name == APPCOMPAT_TEXT_VIEW) {
            return ScaledTextView(context, attrs).apply { syncFromInflation() }
        }
        // 短名（ImageView/Button 等）交给 AppCompat 转换；带包名的全限定类走系统反射
        if ('.' !in name) {
            return viewInflater.createView(context, name, attrs)
        }
        return null
    }
}
