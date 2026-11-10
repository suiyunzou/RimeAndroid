/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * 读取截图的运行时权限。
 *
 * Android 13（API 33）起使用 [Manifest.permission.READ_MEDIA_IMAGES]，
 * 之前的系统使用 [Manifest.permission.READ_EXTERNAL_STORAGE]。
 * 权限声明与 [TransparentActivity] 的注册由宿主（AndroidManifest.xml）完成。
 */
object ScreenshotPermission {
    /** 当前系统版本需要申请的读取图片权限。 */
    val permissionName: String
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

    private var resultListener: WeakReference<(Boolean) -> Unit>? = null

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(
            context,
            permissionName,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 注册一次性授权结果回调；传 null 注销。
     *
     * 只持弱引用，避免输入法长时间存活时把视图或协程作用域拖住。调用方需要把
     * lambda 存到自己的字段里，保证回调触发前不会被回收。
     */
    fun onResult(listener: ((Boolean) -> Unit)?) {
        resultListener = listener?.let { WeakReference(it) }
    }

    /**
     * 打开 [TransparentActivity] 申请权限。已授权时直接回调 true。
     *
     * 从输入法 Service 调用时会自动补 `FLAG_ACTIVITY_NEW_TASK`。
     */
    fun request(
        context: Context,
        listener: ((Boolean) -> Unit)? = null,
    ) {
        if (hasPermission(context)) {
            listener?.invoke(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            listener?.invoke(true)
            return
        }
        onResult(listener)
        val intent = Intent(context, TransparentActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Timber.w(it, "cannot start screenshot permission activity")
                dispatchResult(false)
            }
    }

    internal fun dispatchResult(granted: Boolean) {
        val listener = resultListener?.get()
        resultListener = null
        listener?.invoke(granted)
    }
}

/**
 * 申请读取截图权限的透明 Activity，本身不显示界面。
 *
 * 必须在 AndroidManifest.xml 的 `<application>` 中声明：
 * ```
 * <activity
 *     android:name="com.osfans.trime.ime.screenshot.TransparentActivity"
 *     android:exported="false"
 *     android:excludeFromRecents="true"
 *     android:theme="@android:style/Theme.Translucent.NoTitleBar" />
 * ```
 */
class TransparentActivity : Activity() {
    private var requested = false
    private var pausedAfterRequest = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(savedInstanceState)
        if (ScreenshotPermission.hasPermission(this)) {
            finishWithResult(true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requested = true
            requestPermissions(arrayOf(ScreenshotPermission.permissionName), REQUEST_CODE)
        } else {
            // API 21/22 的读取权限在安装时授予。
            finishWithResult(true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (requested) pausedAfterRequest = true
    }

    override fun onResume() {
        super.onResume()
        // 少数 ROM 在用户作答后不回调 onRequestPermissionsResult，这里按当前
        // 授权状态兜底收尾。系统权限状态此时已经更新，因此结果可靠。
        if (requested && pausedAfterRequest && !delivered) {
            finishWithResult(ScreenshotPermission.hasPermission(this))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE || delivered) return
        val granted =
            grantResults.isNotEmpty() && grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
        finishWithResult(granted)
    }

    @Suppress("DEPRECATION")
    private fun finishWithResult(granted: Boolean) {
        if (delivered) return
        delivered = true
        ScreenshotPermission.dispatchResult(granted)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 0x5C2
    }
}
