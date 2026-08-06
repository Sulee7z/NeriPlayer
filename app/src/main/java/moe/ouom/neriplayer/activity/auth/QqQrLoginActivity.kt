package moe.ouom.neriplayer.activity.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.activity.auth/QqQrLoginActivity
 * Created: 2026/8/6
 */

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.qq.QqQrLoginClient
import moe.ouom.neriplayer.core.api.qq.QqQrLoginSession
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import org.json.JSONObject
import kotlin.math.roundToInt

class QqQrLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "qq_cookie_result"
        private const val LOG_TAG = "NERI-QqQrLogin"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val QR_SIZE_DP = 216
        private const val QQ_BLUE = 0xFF31C27C.toInt()
    }

    private val qrClient by lazy { QqQrLoginClient() }
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var pollJob: Job? = null
    private var hasReturned = false
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: MaterialButton
    private lateinit var webFallbackButton: MaterialButton
    private var pollRound: Int = 0

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        NPLogger.d(LOG_TAG, "Web fallback resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            hasReturned = true
            setResult(RESULT_OK, result.data)
            finish()
            return@registerForActivityResult
        }
        if (!hasReturned) {
            startQrLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("qqmusic")

        buildLayout()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    NPLogger.d(LOG_TAG, "User exits QR login page")
                    finish()
                }
            }
        )
        startQrLogin()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        NPLogger.d(LOG_TAG, "QR login activity destroyed")
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = CoordinatorLayout(this).apply {
            fitsSystemWindows = false
        }
        val surface = root.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = root.materialColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val surfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.rgb(244, 241, 246)
        )
        val onSurfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY
        )
        val onPrimary = root.materialColor(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val softPrimary = ColorUtils.blendARGB(surface, QQ_BLUE, 0.12f)
        val softSurface = ColorUtils.blendARGB(surface, surfaceVariant, 0.18f)

        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(softPrimary, softSurface, surface)
        )

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.qq_qr_login)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 22.dp(), 24.dp(), 48.dp())
        }
        val qrCardSizePx = minOf(240.dp(), resources.displayMetrics.widthPixels - 96.dp()).coerceAtLeast(204.dp())
        val qrImageSizePx = minOf(QR_SIZE_DP.dp(), qrCardSizePx - 24.dp()).coerceAtLeast(180.dp())
        val actionWidthPx = minOf(420.dp(), resources.displayMetrics.widthPixels - 48.dp()).coerceAtLeast(228.dp())

        val titleText = TextView(this).apply {
            text = getString(R.string.qq_qr_login_title)
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
        }
        val subtitleText = TextView(this).apply {
            text = getString(R.string.qq_qr_login_subtitle)
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }

        val qrCard = MaterialCardView(this).apply {
            radius = 28.dp().toFloat()
            cardElevation = 2.dp().toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            preventCornerOverlap = true
            layoutParams = LinearLayout.LayoutParams(qrCardSizePx, qrCardSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        qrImage = ImageView(this).apply {
            background = roundedBackground(Color.WHITE, 22.dp())
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = FrameLayout.LayoutParams(qrImageSizePx, qrImageSizePx, Gravity.CENTER)
        }
        qrCard.addView(qrImage)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(QQ_BLUE)
            layoutParams = FrameLayout.LayoutParams(42.dp(), 42.dp(), Gravity.CENTER)
        }
        qrCard.addView(progressBar)

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(16.dp(), 9.dp(), 16.dp(), 9.dp())
            setTextColor(QQ_BLUE)
            background = roundedBackground(ColorUtils.blendARGB(surface, QQ_BLUE, 0.11f), 20.dp())
        }
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(3.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }
        retryButton = MaterialButton(this).apply {
            text = getString(R.string.qq_qr_login_retry)
            cornerRadius = 20.dp()
            minHeight = 52.dp()
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(QQ_BLUE)
            setTextColor(onPrimary)
            setOnClickListener { startQrLogin() }
        }
        webFallbackButton = MaterialButton(this).apply {
            text = getString(R.string.qq_qr_login_web_fallback)
            cornerRadius = 20.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(surface, QQ_BLUE, 0.10f))
            setTextColor(QQ_BLUE)
            setOnClickListener { openWebFallback() }
        }

        content.addView(titleText, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(subtitleText, matchWidthWrapHeight())
        content.addVerticalSpace(22)
        content.addView(qrCard)
        content.addVerticalSpace(14)
        content.addView(statusText, wrapContentCentered())
        content.addVerticalSpace(10)
        content.addView(hintText, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(16)
        content.addView(retryButton, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(10)
        content.addView(webFallbackButton, fixedWidthWrapHeight(actionWidthPx))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            addView(content)
        }

        root.addView(scrollView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            scrollView.updatePadding(bottom = nav.bottom + 16.dp())
            insets
        }
    }

    private fun startQrLogin() {
        pollJob?.cancel()
        qrClient.reset()
        pollRound = 0
        NPLogger.d(LOG_TAG, "Start QR login")
        pollJob = lifecycleScope.launch {
            setLoadingState(true)
            setStatus(getString(R.string.qq_qr_login_loading))
            hintText.text = getString(R.string.qq_qr_login_hint)
            qrImage.setImageDrawable(null)

            val session = runCatching {
                withContext(Dispatchers.IO) { qrClient.createSession() }
            }.getOrElse { error ->
                setLoadingState(false)
                setErrorStatus(getString(R.string.qq_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Create QR login session failed", error)
                return@launch
            }
            NPLogger.d(
                LOG_TAG,
                "QR session ready qrsig=${session.qrsig.take(4)}...${session.qrsig.takeLast(4)}"
            )

            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(session.qrImageBytes, 0, session.qrImageBytes.size)
            }
            qrImage.setImageBitmap(bitmap)
            setLoadingState(false)
            setStatus(getString(R.string.qq_qr_login_waiting))
            pollQrLogin(session)
        }
    }

    private suspend fun pollQrLogin(session: QqQrLoginSession) {
        while (lifecycleScope.isActive && !hasReturned) {
            pollRound += 1
            NPLogger.d(LOG_TAG, "Poll round=$pollRound")
            val check = runCatching {
                withContext(Dispatchers.IO) { qrClient.checkLogin(session) }
            }.getOrElse { error ->
                setErrorStatus(getString(R.string.qq_qr_login_failed, error.readableMessage()))
                NPLogger.w(LOG_TAG, "Check QR login failed", error)
                return
            }
            NPLogger.d(
                LOG_TAG,
                "Poll round=$pollRound code=${check.code} message=${check.message} cookieKeys=${check.cookies.keys}"
            )

            when {
                check.code == 0 -> {
                    // 尝试补全 qm_keyst 后返回
                    val cookies = withContext(Dispatchers.IO) {
                        qrClient.fetchMusicKeyIfPossible()
                    }
                    finishWithCookies(cookies)
                    return
                }
                check.isExpired -> {
                    setErrorStatus(getString(R.string.qq_qr_login_expired))
                    return
                }
                check.isScanned -> setStatus(getString(R.string.qq_qr_login_scanned))
                check.code == 66 || check.code == 67 -> setStatus(getString(R.string.qq_qr_login_waiting))
                else -> {
                    val message = check.message.ifBlank { "code=${check.code}" }
                    NPLogger.w(LOG_TAG, "Unexpected QR status code=${check.code} message=$message")
                    setErrorStatus(getString(R.string.qq_qr_login_failed, message))
                    return
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun finishWithCookies(cookies: Map<String, String>) {
        if (cookies["uin"].isNullOrBlank() &&
            cookies["p_skey"].isNullOrBlank() &&
            cookies["qm_keyst"].isNullOrBlank()
        ) {
            setErrorStatus(getString(R.string.qq_qr_login_cookie_incomplete))
            NPLogger.w(LOG_TAG, "QR login confirmed but cookie is incomplete, keys=${cookies.keys}")
            return
        }

        hasReturned = true
        val json = JSONObject().apply {
            cookies.forEach { (key, value) -> put(key, value) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(LOG_TAG, "QR login OK, cookie keys=${cookies.keys}")
        finish()
    }

    private fun openWebFallback() {
        pollJob?.cancel()
        NPLogger.d(LOG_TAG, "Open web fallback login")
        webLoginLauncher.launch(Intent(this, QqWebLoginActivity::class.java))
    }

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        retryButton.isEnabled = !loading
        webFallbackButton.isEnabled = true
    }

    private fun setStatus(text: String) {
        statusText.text = text
        NPLogger.d(LOG_TAG, "UI status=$text")
        val surface = statusText.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        statusText.setTextColor(QQ_BLUE)
        statusText.background = roundedBackground(ColorUtils.blendARGB(surface, QQ_BLUE, 0.11f), 20.dp())
    }

    private fun setErrorStatus(text: String) {
        statusText.text = text
        NPLogger.w(LOG_TAG, "UI error=$text")
        val error = statusText.materialColor(androidx.appcompat.R.attr.colorError, Color.RED)
        val surface = statusText.materialColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        statusText.setTextColor(error)
        statusText.background = roundedBackground(
            ColorUtils.blendARGB(surface, error, 0.12f),
            20.dp()
        )
    }

    private fun Throwable.readableMessage(): String {
        return message ?: javaClass.simpleName
    }

    private fun LinearLayout.addVerticalSpace(heightDp: Int) {
        addView(
            View(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp.dp()
            )
        )
    }

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fixedWidthWrapHeight(widthPx: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun wrapContentCentered(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun View.materialColor(attr: Int, fallback: Int): Int {
        return MaterialColors.getColor(this, attr, fallback)
    }

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }
}
