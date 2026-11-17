package com.amusic.app.ui

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * 服务器地址配置弹窗
 */
object ServerConfigDialog {

    fun show(
        context: Context,
        currentUrl: String,
        onUrlUpdated: (newUrl: String) -> Unit
    ) {
        val input = EditText(context).apply {
            setText(currentUrl)
            setSelection(currentUrl.length)
            hint = "http://192.168.x.x:8088 或域名"
        }

        AlertDialog.Builder(context)
            .setTitle("设置 Amusic 服务器地址")
            .setView(input)
            .setPositiveButton("保存并刷新") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    val formattedUrl = if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        "http://$newUrl"
                    } else {
                        newUrl
                    }
                    onUrlUpdated(formattedUrl)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
