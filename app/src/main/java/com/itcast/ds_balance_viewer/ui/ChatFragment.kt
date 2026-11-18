package com.itcast.ds_balance_viewer.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.OnBackPressedCallback
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.widget.*
import android.speech.tts.TextToSpeech
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ChatPreferences
import com.itcast.ds_balance_viewer.data.ChatAttachment
import com.itcast.ds_balance_viewer.data.ChatSource
import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.data.TokenUsage
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.databinding.FragmentChatBinding
import com.itcast.ds_balance_viewer.util.Formatting

class ChatFragment : Fragment(R.layout.fragment_chat) {
    private var binding: FragmentChatBinding? = null
    private val chat by lazy { ViewModelProvider(this)[ChatViewModel::class.java] }
    private var displayed: List<ChatTurn>? = null
    private var liveReasoning: TextView? = null
    private var lastReply: LinearLayout? = null

    private val pending = mutableListOf<ChatAttachment>()
    private var camera: Uri? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var running = false
    /** 程序化回填开关时挡掉回调，避免把「当前不可用」误存成偏好关闭。 */
    private var bindingSearch = false
    /** 历史抽屉的批量删除选择状态。 */
    private var batchMode = false
    private val selectedIds = mutableSetOf<String>()
    private val prism by lazy { CodeGrammars.create() }
    private val codeTheme by lazy {
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark) io.noties.markwon.syntax.Prism4jThemeDarkula.create()
        else io.noties.markwon.syntax.Prism4jThemeDefault.create()
    }
    private val codeHighlight by lazy { io.noties.markwon.syntax.Prism4jSyntaxHighlight.create(prism, codeTheme) }
    private val markdown by lazy {
        Markwon.builder(requireContext().applicationContext)
            .usePlugin(TablePlugin.create(requireContext().applicationContext))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.syntax.SyntaxHighlightPlugin.create(prism, codeTheme))
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureParser(builder: org.commonmark.parser.Parser.Builder) {
                    ReplyMarkdown.configure(builder)
                }

                override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> openWebLink(link) }
                }
            }).build()
    }
    private fun toast(message: String) { context?.let { Toast.makeText(it,message,Toast.LENGTH_SHORT).show() } }

    private fun replyMarkdown(text: String, streaming: Boolean = false): android.text.Spanned =
        markdown.render(ReplyMarkdown.repair(markdown.parse(text), text, streaming))

    private fun renderReply(container: LinearLayout, text: String, streaming: Boolean = false) {
        if (container.tag == text) return
        container.tag = text
        val document = ReplyMarkdown.repair(markdown.parse(text), text, streaming)
        var position = 0
        var prose = org.commonmark.node.Document()

        fun place(view: View) {
            if (container.getChildAt(position) !== view) {
                if (position < container.childCount) container.removeViewAt(position)
                container.addView(view, position, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            position++
        }

        fun renderProse() {
            if (prose.firstChild == null) return
            val view = container.getChildAt(position) as? TextView ?: TextView(container.context).apply {
                textSize = 16f
                setTextIsSelectable(true)
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(binding!!.input.currentTextColor)
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            markdown.setParsedMarkdown(view, markdown.render(prose))
            place(view)
            prose = org.commonmark.node.Document()
        }

        while (document.firstChild != null) {
            when (val node = document.firstChild) {
                is org.commonmark.node.FencedCodeBlock -> {
                    renderProse()
                    val view = container.getChildAt(position) as? ChatCodeView ?: ChatCodeView(container.context)
                    view.bind(node.info, node.literal, codeHighlight, streaming)
                    place(view)
                    node.unlink()
                }
                is org.commonmark.node.IndentedCodeBlock -> {
                    renderProse()
                    val view = container.getChildAt(position) as? ChatCodeView ?: ChatCodeView(container.context)
                    view.bind(null, node.literal, codeHighlight, streaming)
                    place(view)
                    node.unlink()
                }
                else -> prose.appendChild(node)
            }
        }
        renderProse()
        if (position < container.childCount) container.removeViews(position, container.childCount - position)
    }

    private fun openWebLink(link: String) {
        val uri = Uri.parse(link.trim()).normalizeScheme()
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            toast("来源链接无效")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            toast("设备上没有可打开链接的浏览器")
        } catch (_: SecurityException) {
            toast("系统限制了此链接的打开")
        }
    }
    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::addPickedFiles)
    private val gallery = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatAttachment.MAX_PER_MESSAGE), ::addPickedFiles
    )
    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        camera?.let { uri ->
            if (ok) {
                pending += ChatAttachment(uri.toString(), "拍摄照片.jpg", "image/jpeg")
                showAttachment()
            } else requireContext().contentResolver.delete(uri, null, null)
        }
        camera = null
    }

    private fun addPickedFiles(uris: List<Uri>) {
        val ctx = requireContext()
        val resolver = ctx.contentResolver
        var failed = 0
        var limited = false
        for (uri in uris.distinct()) {
            if (pending.any { it.uri == uri.toString() }) continue
            if (pending.size >= ChatAttachment.MAX_PER_MESSAGE) {
                limited = true
                break
            }
            // 只接受外部选择器返回的内容 URI，拍照使用应用自己生成的 URI。
            if (uri.scheme != "content" || uri.authority.isNullOrEmpty() ||
                uri.authority == ctx.packageName + ".fileprovider") {
                failed++
                continue
            }
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "附件"
                pending += ChatAttachment(uri.toString(), name, resolver.getType(uri).orEmpty())
            }.onFailure { failed++ }
        }
        if (limited) toast("每条消息最多添加 20 个附件")
        if (failed > 0) toast("有 " + failed + " 个文件无法读取，请重新选择")
        showAttachment()
    }

    private fun clearAttachment() {
        pending.clear()
        showAttachment()
    }

    private fun showAttachment() {
        val b = binding ?: return
        b.attachmentPreview.isVisible = pending.isNotEmpty()
        fillAttachmentRow(b.attachmentItems, pending) { file ->
            pending.remove(file)
            showAttachment()
        }
    }

    private fun fillAttachmentRow(
        row: LinearLayout, items: List<ChatAttachment>, remove: ((ChatAttachment) -> Unit)? = null
    ) {
        val ctx = row.context
        row.removeAllViews()
        for (file in items) {
            val tile = FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.chat_bubble)
                clipToOutline = true
            }
            if (file.isImage) {
                val image = ImageView(ctx).apply {
                    contentDescription = "预览 " + file.name
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener { previewImage(file) }
                }
                tile.addView(image, FrameLayout.LayoutParams(-1, -1))
                Glide.with(this).load(Uri.parse(file.uri)).override(dp(160)).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE).error(android.R.drawable.ic_menu_gallery).into(image)
            } else {
                tile.addView(TextView(ctx).apply {
                    text = file.name
                    textSize = 13f
                    gravity = Gravity.BOTTOM
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(10), dp(10), dp(8))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            if (remove != null) {
                tile.addView(ImageButton(ctx).apply {
                    contentDescription = "移除 " + file.name
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setBackgroundResource(R.drawable.chat_surface)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { remove(file) }
                }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(if (file.isImage) 80 else 126), dp(80)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun previewImage(file: ChatAttachment) {
        val ctx = requireContext()
        val image = ImageView(ctx).apply { adjustViewBounds = true }
        val dialog = AlertDialog.Builder(ctx).setTitle(file.name).setView(image)
            .setPositiveButton("关闭", null).create()
        dialog.setOnDismissListener { Glide.with(ctx).clear(image) }
        dialog.show()
        val size = resources.displayMetrics.widthPixels
        Glide.with(this).load(Uri.parse(file.uri)).override(size, size).fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.NONE).error(android.R.drawable.ic_menu_gallery).into(image)
    }

    private fun attachments() {
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        content.addView(TextView(ctx).apply {
            text = if (pending.isEmpty()) "添加附件" else "已选 " + pending.size + " / " + ChatAttachment.MAX_PER_MESSAGE
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        if (pending.isNotEmpty()) {
            content.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(ctx).also { fillAttachmentRow(it, pending) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val row = LinearLayout(ctx)
        val actions = listOf(
            Triple("拍照", android.R.drawable.ic_menu_camera, {
                val dir = File(ctx.filesDir, "chat_photos").apply { mkdirs() }
                val file = File.createTempFile("photo_", ".jpg", dir)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
                camera = uri
                try { photo.launch(uri) } catch (error: ActivityNotFoundException) {
                    camera = null
                    file.delete()
                    throw error
                }
            }),
            Triple("相册", android.R.drawable.ic_menu_gallery, {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }),
            Triple("文件", android.R.drawable.ic_menu_save, { documents.launch(arrayOf("*/*")) })
        )
        actions.forEach { (label, icon, open) ->
            row.addView(com.google.android.material.button.MaterialButton(ctx).apply {
                text = label
                setIconResource(icon)
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_TOP
                iconPadding = dp(4)
                isEnabled = pending.size < ChatAttachment.MAX_PER_MESSAGE
                setOnClickListener {
                    sheet.dismiss()
                    runCatching(open).onFailure { toast("系统无法打开" + label) }
                }
            }, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(4) })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        sheet.setContentView(content)
        sheet.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending", ChatAttachment.encode(pending))
        outState.putString("camera",camera?.toString())
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentChatBinding.bind(view)
        binding = b
        savedInstanceState?.getString("pending")?.let {
            pending.clear()
            pending.addAll(ChatAttachment.decode(it))
        }
        camera = savedInstanceState?.getString("camera")?.let(Uri::parse) ?: camera
        showAttachment()
        b.attach.setOnClickListener { attachments() }
        b.jumpLatest.setOnClickListener { b.scroll.smoothScrollTo(0, b.messages.height) }
        b.scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            b.jumpLatest.isVisible = b.scroll.canScrollVertically(1)
        }
        val toolbarHeight = b.header.layoutParams.height
        b.historyPanel.layoutParams = b.historyPanel.layoutParams.apply { width = (resources.displayMetrics.widthPixels * .82f).toInt() }
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { b.root.closeDrawer(b.historyPanel) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,back)
        b.root.setScrimColor(android.graphics.Color.TRANSPARENT)
        b.root.drawerElevation = 0f
        b.root.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val direction = if (b.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                b.chatPage.translationX = drawerView.width * slideOffset * direction
                val lightTheme = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (!isHidden) androidx.core.view.WindowCompat.getInsetsController(requireActivity().window,b.root)
                    .isAppearanceLightStatusBars = lightTheme && slideOffset > 0.5f
                back.isEnabled = !isHidden && slideOffset > 0f
            }
            override fun onDrawerOpened(drawerView: View) { refreshHistory() }
            override fun onDrawerClosed(drawerView: View) {
                requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(drawerView.windowToken,0)
                b.historySearch.clearFocus()
                if (batchMode) exitBatchMode()
            }
        })
        b.historySearch.doAfterTextChanged { refreshHistory() }
        b.batchAll.setOnClickListener { visibleSessionIds().let { selectedIds.addAll(it); refreshHistory() } }
        b.batchNone.setOnClickListener { selectedIds.clear(); refreshHistory() }
        b.batchExit.setOnClickListener { exitBatchMode() }
        b.batchDelete.setOnClickListener {
            val ids = selectedIds.toSet()
            if (ids.isEmpty()) { toast("请先选择要删除的对话"); return@setOnClickListener }
            AlertDialog.Builder(requireContext()).setTitle("批量删除")
                .setMessage("删除选中的 ${ids.size} 段对话及其所有回答？此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    val count = chat.deleteMany(ids)
                    exitBatchMode()
                    toast("已删除 $count 段对话")
                }.show()
        }
        val windowPosition = IntArray(2)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // DrawerLayout 按子视图的边距测量和摆放页面。键盘留白应交给实际排列输入框的布局。
            view.getLocationInWindow(windowPosition)
            val pageBottom = windowPosition[1] + view.height
            val keyboardTop = view.rootView.height - keyboard
            val overlap = (pageBottom - keyboardTop).coerceIn(0, keyboard)
            b.chatPage.setPadding(bars.left, 0, bars.right, overlap)
            b.header.setPadding(b.header.paddingLeft, bars.top, b.header.paddingRight, 0)
            if (b.header.layoutParams.height != toolbarHeight + bars.top) b.header.layoutParams = b.header.layoutParams.apply { height = toolbarHeight + bars.top }
            b.historyPanel.setPadding(dp(16) + bars.left, bars.top, dp(16), overlap)
            insets
        }
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) ViewCompat.requestApplyInsets(v)
        }
        b.setup.setOnClickListener { settings() }
        b.settings.setOnClickListener { settings() }
        b.model.setOnClickListener { settings() }
        b.newChat.setOnClickListener { chat.open(); b.input.text.clear(); clearAttachment() }
        b.history.setOnClickListener { refreshHistory(); b.root.openDrawer(b.historyPanel) }
        b.thinking.isChecked = ChatPreferences.thinking
        b.thinking.setOnCheckedChangeListener { _, value -> ChatPreferences.thinking = value }
        bindingSearch = true
        b.search.isChecked = ChatPreferences.search && searchSupported()
        bindingSearch = false
        b.search.setOnCheckedChangeListener { _, value ->
            if (bindingSearch) return@setOnCheckedChangeListener
            ChatPreferences.search = value
        }
        b.send.setOnClickListener {
            if (chat.state.value?.busy == true) {
                chat.stop()
            } else {
                val question = b.input.text.toString().trim()
                when {
                    ChatPreferences.key == null -> settings()
                    pending.isNotEmpty() && !flash() -> toast("请在对话设置中选择 DeepSeek Flash 发送附件")
                    question.isEmpty() && pending.isEmpty() -> b.input.requestFocus()
                    chat.send(question.ifEmpty { "请分析这些附件" }, attachment = ChatAttachment.encode(pending), search = searchSupported() && b.search.isChecked) -> {
                        clearAttachment()
                        b.input.text.clear()
                        requireContext().getSystemService(InputMethodManager::class.java)
                            ?.hideSoftInputFromWindow(b.input.windowToken, 0)
                    }
                }
            }
        }
        chat.state.observe(viewLifecycleOwner) { render(it) }
    }

    override fun onResume() { super.onResume(); refreshKey() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) refreshKey() else {
        binding?.root?.closeDrawers()
        activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightStatusBars = false }
    } }

    private fun searchSupported() = ChatPreferences.key?.provider == com.itcast.ds_balance_viewer.data.Provider.DEEPSEEK
    private fun flash() = ChatPreferences.key?.let { ChatPreferences.model(it.provider) } == "deepseek-flash"
    private fun refreshKey() {
        val b = binding ?: return
        val key = ChatPreferences.key
        b.model.text = chat.state.value?.turns?.firstOrNull()?.question ?: key?.let { ChatPreferences.model(it.provider) } ?: "对话"
        b.search.isEnabled = searchSupported() && !running
        bindingSearch = true
        b.search.isChecked = ChatPreferences.search && searchSupported()
        bindingSearch = false
        b.setup.isVisible = key == null
    }

    private fun settings() {
        val keys = KeyRepository.all()
        if (keys.isEmpty()) {
            AlertDialog.Builder(requireContext()).setTitle("对话设置")
                .setMessage("先添加自己的 API 密钥，对话费用将从对应的平台账户扣除。")
                .setPositiveButton("添加密钥") { _, _ -> addKey() }.setNegativeButton("取消", null).show()
            return
        }
        val ctx = requireContext()
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        fun field(label: String): Spinner {
            form.addView(TextView(ctx).apply { text = label })
            return Spinner(ctx, Spinner.MODE_DIALOG).apply { prompt = label; form.addView(this, -1, dp(52)) }
        }
        fun options(values: List<String>) = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, values)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val account = field("使用余额页中的 API")
        val model = field("对话模型")
        account.adapter = options(keys.map { it.provider.shortName + " · " + it.displayName() + " · " + Formatting.mask(it.secret) })
        fun updateModels(index: Int) {
            val provider = keys[index].provider
            val models = ChatPreferences.models(provider)
            model.adapter = options(models)
            model.setSelection(models.indexOf(ChatPreferences.model(provider)).coerceAtLeast(0))
        }
        val selected = keys.indexOfFirst { it.id == ChatPreferences.keyId }.coerceAtLeast(0)
        account.setSelection(selected)
        updateModels(selected)
        account.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateModels(position)
        }
        form.addView(TextView(ctx).apply { text = "发送时，当前对话的上下文会交给所选平台，并使用这把密钥计费。"; textSize = 13f })
        AlertDialog.Builder(ctx).setTitle("对话设置").setView(form)
            .setNeutralButton("添加密钥") { _, _ -> addKey() }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val key = keys[account.selectedItemPosition]
                ChatPreferences.keyId = key.id
                ChatPreferences.setModel(key.provider, model.selectedItem.toString())
                refreshKey()
            }.show()
    }

    private fun addKey() = startActivity(Intent(requireContext(), AddKeyActivity::class.java))

    private fun visibleSessionIds(): List<String> =
        binding?.historySearch?.text?.toString()?.let { chat.sessions(it).map { s -> s.id } } ?: emptyList()

    private fun exitBatchMode() {
        batchMode = false
        selectedIds.clear()
        refreshHistory()
    }

    private fun refreshHistory() {
        val b = binding ?: return
        val ctx = requireContext()
        val rows = b.historyRows
        val search = b.historySearch
        b.batchBar.isVisible = batchMode
        b.batchDelete.isVisible = batchMode
        if (batchMode) {
            b.batchCount.text = "已选 ${selectedIds.size} 项"
            b.batchAll.isEnabled = visibleSessionIds().any { it !in selectedIds }
            b.batchNone.isEnabled = selectedIds.isNotEmpty()
            b.batchDelete.isEnabled = selectedIds.isNotEmpty()
        }
        rows.removeAllViews()
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR,-1) }
        val groups = chat.sessions(search.text.toString()).groupBy {
            when { it.pinned -> "置顶"; it.updated >= today.timeInMillis -> "今天"; it.updated >= yesterday.timeInMillis -> "昨天"; else -> "更早" }
        }
        if (groups.isEmpty()) rows.addView(TextView(ctx).apply { text = if (search.length() > 0) "没有匹配的对话" else "暂无历史对话"; setPadding(0,dp(24),0,0) })
        groups.forEach { (group, items) ->
            rows.addView(TextView(ctx).apply { text = group; textSize = 13f; setPadding(dp(6),dp(22),0,dp(6)) })
            items.forEach { item ->
                val title = TextView(ctx).apply {
                    text = item.title; textSize = 17f; gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(6),0,dp(6),0)
                    setTextColor(binding!!.input.currentTextColor)
                }
                if (batchMode) {
                    val check = CheckBox(ctx).apply {
                        isChecked = item.id in selectedIds
                        isClickable = false
                        isFocusable = false
                        buttonTintList = android.content.res.ColorStateList.valueOf(b.input.currentTextColor)
                    }
                    val row = LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6),0,dp(6),0)
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        isFocusable = true
                        addView(check, dp(34), dp(48))
                        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                        setOnClickListener {
                            if (item.id in selectedIds) selectedIds.remove(item.id) else selectedIds.add(item.id)
                            check.isChecked = item.id in selectedIds
                            refreshHistory()
                        }
                    }
                    rows.addView(row, -1, dp(52))
                } else {
                    title.setPadding(dp(6),0,dp(6),0)
                    title.setOnClickListener {
                        chat.open(item.id); clearAttachment()
                        b.input.text.clear(); b.root.closeDrawer(b.historyPanel)
                    }
                    title.setOnLongClickListener {
                        AlertDialog.Builder(ctx).setItems(arrayOf(if (item.pinned) "取消置顶" else "置顶","删除对话","批量选择")) { _, choice ->
                            when (choice) {
                                0 -> { chat.pin(item); refreshHistory() }
                                1 -> AlertDialog.Builder(ctx).setMessage("删除这段对话和所有回答版本？")
                                    .setNegativeButton("取消",null).setPositiveButton("删除") { _, _ -> chat.delete(item.id); refreshHistory() }.show()
                                else -> { batchMode = true; selectedIds.add(item.id); refreshHistory() }
                            }
                        }.show()
                        true
                    }
                    rows.addView(title, -1, dp(52))
                }
            }
        }
    }

    /** 回答下方的 token 用量小字。旧数据（usage 全为 0）不显示。 */
    private fun showUsage(usage: TokenUsage) {
        if (!usage.hasData) return
        val b = binding ?: return
        fun grouped(value: Int) = String.format(java.util.Locale.US, "%,d", value)
        val text = buildString {
            append("↑ 输入 ").append(grouped(usage.prompt))
            append("  ·  ↓ 输出 ").append(grouped(usage.completion))
            if (usage.reasoning > 0) append("（含思考 ").append(grouped(usage.reasoning)).append("）")
            append("  ·  合计 ").append(grouped(usage.total)).append(" tokens")
        }
        b.messages.addView(TextView(b.messages.context).apply {
            this.text = text
            textSize = 12f
            setTextColor(b.notice.currentTextColor)
            setPadding(dp(2), 0, dp(2), dp(2))
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
    }

    private fun showSources(sources: List<ChatSource>) {
        if (sources.isEmpty()) return
        val b = binding ?: return
        val ctx = b.messages.context
        val muted = b.notice.currentTextColor
        val primaryText = b.input.currentTextColor
        val card = MaterialCardView(ctx).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ctx.getColor(R.color.ds_divider)
            setCardBackgroundColor(ctx.getColor(R.color.ds_card))
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(content)
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.chat_next)
            setColorFilter(muted)
            rotation = 90f
        }
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isFocusable = true
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_mode_search)
                setColorFilter(ctx.getColor(R.color.mode_ink))
            }, dp(18), dp(18))
            addView(TextView(ctx).apply {
                text = "检索来源 · ${sources.size}"
                textSize = 14f
                setTextColor(primaryText)
                setPaddingRelative(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, dp(18), dp(18))
        }
        content.addView(header, -1, dp(48))
        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        content.addView(rows, -1, -2)
        header.setOnClickListener {
            rows.isVisible = !rows.isVisible
            chevron.rotation = if (rows.isVisible) 270f else 90f
        }
        sources.forEachIndexed { index, source ->
            val host = Uri.parse(source.url).host.orEmpty().removePrefix("www.")
            rows.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.ds_divider)) }, -1, dp(1))
            rows.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isFocusable = true
                setOnClickListener { openWebLink(source.url) }
                addView(TextView(ctx).apply {
                    text = (index + 1).toString()
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.mode_ink))
                    setBackgroundResource(R.drawable.chat_bubble)
                    gravity = Gravity.CENTER
                }, dp(24), dp(24))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(dp(10), 0, dp(8), 0)
                    addView(TextView(ctx).apply {
                        text = source.title.ifBlank { host }
                        textSize = 14f
                        setTextColor(primaryText)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = host
                        textSize = 12f
                        setTextColor(muted)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_browser)
                    setColorFilter(muted)
                }, dp(16), dp(16))
            }, -1, -2)
        }
        b.messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun actions(turn: ChatTurn) {
        val b = binding ?: return
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        fun action(label: String, icon: Int, enabled: Boolean = true, selected: Boolean = false, click: () -> Unit) {
            row.addView(ImageButton(ctx).apply {
                contentDescription = label
                setImageResource(icon)
                setBackgroundResource(android.R.color.transparent)
                setColorFilter(if (selected) ctx.getColor(R.color.ds_primary) else b.notice.currentTextColor)
                isEnabled = enabled
                alpha = if (enabled) 0.85f else 0.25f
                setOnClickListener { click() }
            },dp(38),dp(44))
        }
        if (turn.versions.size > 1) {
            action("上一版本",R.drawable.chat_prev,turn.selected > 0) { chat.choose(turn,turn.selected-1) }
            row.addView(TextView(ctx).apply { text = "${turn.selected+1}/${turn.versions.size}"; textSize = 12f })
            action("下一版本",R.drawable.chat_next,turn.selected+1 < turn.versions.size) { chat.choose(turn,turn.selected+1) }
        }
        if (turn.answer.isNotEmpty()) {
            val exported = buildString {
                append(turn.answer)
                if (turn.sources.isNotEmpty()) {
                    append("\n\n检索来源\n")
                    turn.sources.forEach { appendLine("${it.title}  ${it.url}") }
                }
            }.trimEnd()
            action("复制回答",R.drawable.chat_copy) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("回答",exported))
                toast("已复制回答")
            }
            action("有帮助",R.drawable.chat_like,selected = turn.vote == 1) { chat.vote(turn,1) }
            action("没帮助",R.drawable.chat_dislike,selected = turn.vote == -1) { chat.vote(turn,-1) }
            action("朗读或停止",R.drawable.chat_speak) { readAloud(replyMarkdown(turn.answer).toString()) }
            action("分享回答",R.drawable.chat_share) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,exported),"分享回答"))
            }
        }
        action("重新生成",R.drawable.ic_refresh) { if (!chat.send(null,turn.id,search = b.search.isChecked && searchSupported())) settings() }
        b.messages.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun readAloud(text: String) {
        fun speak() {
            val engine = speech ?: return
            if (engine.isSpeaking) { engine.stop(); return }
            text.chunked(TextToSpeech.getMaxSpeechInputLength()).forEachIndexed { i, chunk ->
                engine.speak(chunk,if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"reply-" + i)
            }
        }
        if (speechReady) speak()
        else if (speech == null) speech = TextToSpeech(requireContext().applicationContext) { status ->
            speechReady = status == TextToSpeech.SUCCESS && speech != null
            if (speechReady) speak() else toast("系统朗读服务不可用")
        }
    }

    private fun render(state: Conversation) {
        val b = binding ?: return
        val changed = displayed !== state.turns || running != state.busy
        running = state.busy
        val follow = displayed?.lastOrNull()?.id != state.turns.lastOrNull()?.id || !b.scroll.canScrollVertically(1)
        if (changed) {
            b.messages.removeAllViews()
            liveReasoning = null
            lastReply = null
            for (turn in state.turns) {
                addMessage(turn.question, true)
                val files = ChatAttachment.decode(turn.attachment)
                if (files.isNotEmpty()) {
                    b.messages.addView(HorizontalScrollView(requireContext()).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(LinearLayout(context).also { fillAttachmentRow(it, files) })
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                }
                val activeTurn = state.busy && state.target == turn.id
                if (activeTurn || turn.reasoning.isNotBlank()) {
                    val thought = addMessage("",false).apply {
                        textSize = 14f
                        setTextColor(b.notice.currentTextColor)
                        setBackgroundResource(R.drawable.chat_bubble)
                    }
                    if (activeTurn) liveReasoning = thought
                    else {
                        thought.text = "查看思考过程  ▾"
                        var expanded = false
                        thought.setOnClickListener {
                            expanded = !expanded
                            thought.text = if (expanded) replyMarkdown("收起思考过程  ▴\n\n" + turn.reasoning) else "查看思考过程  ▾"
                        }
                    }
                }
                val reply = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                b.messages.addView(reply, -1, -2)
                if (state.busy && state.target == turn.id) lastReply = reply
                else {
                    renderReply(reply, turn.answer.ifEmpty { "尚未完成回复" })
                    showUsage(turn.usage)
                    if (turn.outcome.isNotEmpty()) addMessage(turn.outcome, false).apply {
                        textSize = 13f
                        setTextColor(b.notice.currentTextColor)
                    }
                    showSources(turn.sources)
                    if (!state.busy) actions(turn)
                }
            }
            displayed = state.turns
        }
        val answer = if (state.busy) state.partial.ifEmpty {
            if (ChatPreferences.thinking) "思考中…" else "正在回复…"
        } else state.turns.lastOrNull()?.answer.orEmpty().ifEmpty { "尚未完成回复" }
        liveReasoning?.apply {
            isVisible = state.reasoning.isNotBlank()
            text = replyMarkdown("思考过程\n\n" + state.reasoning, streaming = state.busy)
        }
        lastReply?.let { renderReply(it, answer, streaming = state.busy) }
        refreshKey()
        b.welcome.isVisible = state.turns.isEmpty()
        b.notice.text = state.notice
        b.notice.isVisible = state.notice.isNotEmpty()
        b.attach.isEnabled = !state.busy
        b.search.isEnabled = !state.busy && searchSupported()
        b.settings.isEnabled = !state.busy
        b.model.isEnabled = !state.busy
        b.thinking.isEnabled = !state.busy
        b.newChat.isEnabled = !state.busy
        b.history.isEnabled = !state.busy
        if (changed) {
            b.root.setDrawerLockMode(if (state.busy) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
            refreshHistory()
        }
        b.send.setImageResource(if (state.busy) R.drawable.ic_stop_chat else R.drawable.ic_send_chat)
        b.send.contentDescription = if (state.busy) "停止生成" else "发送"
        b.scroll.post {
            if (follow) b.scroll.scrollTo(0, b.messages.height)
            b.jumpLatest.isVisible = b.scroll.canScrollVertically(1)
        }
    }

    private fun addMessage(content: String, user: Boolean): TextView {
        val box = binding!!.messages
        return TextView(box.context).apply {
            text = content
            textSize = 16f
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (user) setBackgroundResource(R.drawable.chat_bubble)
            setTextColor(binding!!.input.currentTextColor)
            setPadding(dp(if (user) 14 else 2), dp(10), dp(if (user) 14 else 2), dp(6))
            box.addView(this, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = if (user) Gravity.END else Gravity.START
                if (user) marginStart = dp(32)
                bottomMargin = dp(12)
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        lastReply = null
        displayed = null
        liveReasoning = null
        speech?.shutdown(); speech = null; speechReady = false
        binding = null
        super.onDestroyView()
    }
}
