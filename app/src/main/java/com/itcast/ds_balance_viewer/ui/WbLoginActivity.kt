package com.itcast.ds_balance_viewer.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.WbAccount
import com.itcast.ds_balance_viewer.data.WbRepository
import com.itcast.ds_balance_viewer.databinding.ActivityWbLoginBinding
import com.itcast.ds_balance_viewer.net.WorkBuddyClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.Jwt
import com.itcast.ds_balance_viewer.util.SystemBars
import java.util.Locale

/**
 * WorkBuddy 登录页（WebView + 轮询取令牌）。
 *
 * 流程（与桌面端一致，已实测可用）：
 *  1. POST /v2/plugin/auth/state?platform=mobile 拿到 state 和登录页地址
 *  2. 在 WebView 里打开登录页，用户自行完成登录
 *  3. App 每 2 秒轮询 GET /v2/plugin/auth/token?state=xxx
 *     未完成时服务端返回 code=11217，完成后返回 accessToken / refreshToken
 *  4. 拿到令牌后存入加密仓库，返回签到页
 *
 * 登录方式说明（登录页实测支持这些渠道）：
 *  - 手机号验证码 / 邮箱验证码：可直接在 WebView 内完成，最稳
 *  - 微信扫码 / 微信服务号 / QQ / 企业微信：需要跳转外部 App 或摄像头扫码，
 *    WebView 内无法闭环，所以这里做了三件事：
 *      ① 非 http(s) 协议用系统 Intent 唤起对应 App，而不是静默拦掉
 *      ② 提供「在浏览器打开」，让用户能在系统浏览器里走完整登录
 *      ③ 提供「我已登录」手动检查，用户从微信/浏览器回来后一键取令牌
 *    state 存在服务端，任何地方完成登录，App 轮询都能拿到令牌。
 */
class WbLoginActivity : AppCompatActivity() {

    companion object {
        const val RESULT_LOGGED_IN = 1001
        private const val POLL_INTERVAL_MS = 2_000L

        /** 自动轮询 10 分钟；超时只暂停不退出，用户随时可以手动检查 */
        private const val MAX_ATTEMPTS = 300
    }

    private lateinit var binding: ActivityWbLoginBinding
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var polling = true

    /** 防止「定时器 + 手动检查」同时发请求导致重复轮询链 */
    private var inFlight = false
    private var attempts = 0
    private var loginState: String? = null
    private var loginUrl: String? = null
    private var finished = false
    private var wechatWarned = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling || finished) return
            if (attempts >= MAX_ATTEMPTS) {
                pausePolling(R.string.wb_wait_timeout)
                return
            }
            attempts++
            pollOnce()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWbLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(binding.toolbar, binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_check_now -> {
                    checkNow()
                    true
                }
                R.id.action_open_browser -> {
                    openInBrowser()
                    true
                }
                else -> false
            }
        }
        binding.btnCheck.setOnClickListener { checkNow() }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = WebSettings.getDefaultUserAgent(this@WbLoginActivity)
        }
        // 登录链路跨多个域（codebuddy.cn / tencent.com），放开第三方 Cookie 能少踩坑
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                val host = uri.host?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https") {
                    // 登录页的「微信登录」跳的是微信小程序短链（wxmpurl.cn），
                    // 登录态留在小程序里、不会回传到本页的 state，走进去只会卡死。
                    // 直接拦下，引导改用验证码登录或系统浏览器。
                    if (isWechatMiniProgram(host)) {
                        warnWechatOnly()
                        return true
                    }
                    return false
                }
                if (scheme.startsWith("weixin") || scheme.startsWith("wechat")) {
                    // weixin://dl/business/... 同样是拉起小程序，登录结果回不来
                    warnWechatOnly()
                    return true
                }
                // 其他自定义协议（workbuddy://、QQ 等）交给系统唤起
                return launchExternal(uri)
            }
        }

        startLogin()
    }

    /** 第一步：申请登录态并加载登录页 */
    private fun startLogin() {
        binding.progress.visibility = View.VISIBLE
        binding.tvWait.text = getString(R.string.wb_wait_status)
        AppExecutors.io.execute {
            val res = WorkBuddyClient.createLoginState()
            AppExecutors.main {
                when (res) {
                    is WorkBuddyClient.Res.Ok -> {
                        loginState = res.value.state
                        loginUrl = res.value.authUrl
                        binding.webView.loadUrl(res.value.authUrl)
                        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                    }
                    is WorkBuddyClient.Res.Err -> {
                        binding.progress.visibility = View.GONE
                        Toast.makeText(this, R.string.wb_login_state_failed, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    /** 轮询一次登录结果 */
    private fun pollOnce() {
        val state = loginState ?: return
        if (inFlight || finished) return
        inFlight = true
        AppExecutors.io.execute {
            val result = WorkBuddyClient.pollToken(state)
            AppExecutors.main {
                inFlight = false
                if (finished) return@main
                when (result) {
                    is WorkBuddyClient.PollResult.Pending -> {
                        updateWaitLabel()
                        if (polling) handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                    }
                    is WorkBuddyClient.PollResult.Done -> onLoggedIn(result.tokens)
                    is WorkBuddyClient.PollResult.Failed -> {
                        // 不直接退出：可能只是网络抖动，继续重试，同时把原因显示出来
                        binding.tvWait.text = getString(R.string.wb_poll_error, result.message)
                        if (polling) handler.postDelayed(pollRunnable, POLL_INTERVAL_MS * 2)
                    }
                }
            }
        }
    }

    /** 手动触发一次检查（从微信 / 浏览器回到 App 后使用） */
    private fun checkNow() {
        if (finished) return
        if (loginState.isNullOrBlank()) {
            Toast.makeText(this, R.string.wb_login_state_failed, Toast.LENGTH_SHORT).show()
            return
        }
        polling = true
        attempts = 0
        inFlight = false
        handler.removeCallbacks(pollRunnable)
        binding.waitSpin.visibility = View.VISIBLE
        binding.tvWait.text = getString(R.string.wb_login_checking)
        pollOnce()
    }

    private fun updateWaitLabel() {
        val sec = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val clock = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        binding.tvWait.text = getString(R.string.wb_wait_status) + " " + clock
    }

    private fun pausePolling(msgRes: Int) {
        polling = false
        binding.waitSpin.visibility = View.GONE
        binding.tvWait.text = getString(msgRes)
    }

    /** 微信小程序登录入口的域名（拉起小程序后登录态回不到网页，必须绕开） */
    private fun isWechatMiniProgram(host: String): Boolean =
        host.contains("wxmpurl.cn") ||
            host.contains("wxaurl") ||
            host.contains("open.weixin.qq.com") ||
            host.contains("mp.weixin.qq.com")

    /** 微信登录只能在小程序里完成，登录态回传不了，这里拦下并给出替代方案 */
    private fun warnWechatOnly() {
        if (wechatWarned) return
        wechatWarned = true
        binding.waitSpin.visibility = View.GONE
        binding.tvWait.text = getString(R.string.wb_wechat_hint_bar)
        AlertDialog.Builder(this)
            .setTitle(R.string.wb_wechat_title)
            .setMessage(R.string.wb_wechat_only)
            .setPositiveButton(R.string.wb_open_browser) { _, _ -> openInBrowser() }
            .setNegativeButton(R.string.wb_wechat_use_phone, null)
            .show()
    }

    /** 唤起系统里能处理该协议的 App（微信 / QQ / 企业微信等） */
    private fun launchExternal(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty().lowercase()
        val label = when {
            scheme.startsWith("weixin") || scheme.startsWith("wx") -> "微信"
            scheme.startsWith("workbuddy") || scheme.startsWith("codebuddy") -> "WorkBuddy"
            scheme.startsWith("mqq") || scheme == "qq" || scheme.startsWith("tim") -> "QQ"
            scheme.startsWith("wxwork") -> "企业微信"
            else -> scheme
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            Toast.makeText(this, getString(R.string.wb_app_jump, label), Toast.LENGTH_LONG).show()
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.wb_app_jump_failed, label), Toast.LENGTH_LONG).show()
            true
        }
    }

    /** 用系统浏览器打开当前登录页（微信登录在浏览器里成功率更高） */
    private fun openInBrowser() {
        val current = binding.webView.url
        val url = current?.takeIf { it.startsWith("http", ignoreCase = true) } ?: loginUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.wb_login_state_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            Toast.makeText(this, R.string.wb_browser_opened, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wb_browser_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 第三步：保存账号 */
    private fun onLoggedIn(tokens: WorkBuddyClient.Tokens) {
        if (finished) return
        polling = false
        handler.removeCallbacks(pollRunnable)

        val claims = Jwt.parse(tokens.accessToken)
        val uid = claims?.sub.orEmpty()
        if (uid.isBlank()) {
            Toast.makeText(this, getString(R.string.wb_login_failed, "令牌缺少账号标识"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val expireAt = when {
            claims != null && claims.expSeconds > 0 -> claims.expSeconds * 1000L
            else -> now + tokens.expiresIn * 1000L
        }
        val alias = claims?.username?.takeIf { it.isNotBlank() }
            ?: uid.takeLast(8)

        val existing = WbRepository.findByUid(uid)
        val account = (existing ?: WbAccount(uid = uid, accessToken = tokens.accessToken)).copy(
            alias = existing?.alias?.ifBlank { alias } ?: alias,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: existing?.refreshToken,
            domain = tokens.domain ?: existing?.domain,
            accessExpireAt = expireAt,
            refreshExpireAt = now + tokens.refreshExpiresIn * 1000L
        )
        if (existing == null) WbRepository.add(account) else WbRepository.update(account)

        finished = true
        binding.waitBar.visibility = View.GONE
        binding.doneState.visibility = View.VISIBLE
        binding.tvDoneDesc.text = getString(R.string.wb_login_saved)
        handler.postDelayed({
            setResult(RESULT_LOGGED_IN)
            finish()
        }, 900)
    }

    override fun onResume() {
        super.onResume()
        // 从微信 / 浏览器切回来：自动重新开始检查，用户不用再点任何东西
        if (finished || loginState == null || polling) return
        polling = true
        attempts = 0
        inFlight = false
        handler.removeCallbacks(pollRunnable)
        binding.waitSpin.visibility = View.VISIBLE
        handler.postDelayed(pollRunnable, 300)
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        if (::binding.isInitialized) {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
