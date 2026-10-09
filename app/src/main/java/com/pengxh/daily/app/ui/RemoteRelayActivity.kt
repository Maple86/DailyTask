package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityRemoteRelayBinding
import com.pengxh.daily.app.remote.RemoteRelayCoordinator
import com.pengxh.daily.app.remote.RemoteRelayStore
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteRelayActivity : KotlinBaseActivity<ActivityRemoteRelayBinding>() {
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }

    override fun initViewBinding(): ActivityRemoteRelayBinding {
        return ActivityRemoteRelayBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        RemoteRelayStore.initialize(this)
        binding.endpointInput.setText(RemoteRelayStore.endpoint)
        binding.deviceNameInput.setText(
            RemoteRelayStore.deviceName.ifBlank { Build.MODEL }
        )
        binding.enabledSwitch.isChecked = RemoteRelayStore.enabled
        refreshStatus()
    }

    override fun observeRequestState() = Unit

    override fun initEvent() {
        binding.pairButton.setOnClickListener {
            val endpoint = binding.endpointInput.text.toString().trim()
            val code = binding.pairingCodeInput.text.toString().trim()
            val deviceName = binding.deviceNameInput.text.toString().trim().ifBlank { Build.MODEL }

            if (endpoint.isBlank() ||
                !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))
            ) {
                "请填写正确的中继地址（http:// 或 https://）".show(this)
                return@setOnClickListener
            }
            val endpointHost = runCatching { java.net.URI(endpoint).host }.getOrNull().orEmpty()
            if (endpointHost.equals("localhost", ignoreCase = true) || endpointHost == "127.0.0.1") {
                "localhost 指向手机自身，真机请填写电脑局域网 IP".show(this)
                return@setOnClickListener
            }
            if (code.length != 6) {
                "请输入小程序生成的 6 位配对码".show(this)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                LoadingDialog.show(this@RemoteRelayActivity, "正在配对...")
                runCatching {
                    RemoteRelayCoordinator.pair(endpoint, code, deviceName)
                }.onSuccess { response ->
                    if (response.ok) {
                        binding.pairingCodeInput.setText("")
                        binding.enabledSwitch.isChecked = true
                        refreshStatus()
                        ContextCompat.startForegroundService(
                            this@RemoteRelayActivity,
                            Intent(this@RemoteRelayActivity, ForegroundRunningService::class.java)
                        )
                        RemoteRelayCoordinator.syncNow()
                        "配对成功".show(this@RemoteRelayActivity)
                    } else {
                        "配对失败：${response.errorMessage}".show(this@RemoteRelayActivity)
                    }
                }.onFailure {
                    "配对失败：${it.message ?: "未知错误"}".show(this@RemoteRelayActivity)
                }
                LoadingDialog.dismiss()
            }
        }

        binding.enabledSwitch.setOnCheckedChangeListener { _, enabled ->
            RemoteRelayStore.endpoint = binding.endpointInput.text.toString()
            RemoteRelayStore.deviceName = binding.deviceNameInput.text.toString()
            RemoteRelayStore.enabled = enabled
            if (enabled) {
                if (!RemoteRelayStore.isConfigured()) {
                    "请先完成设备配对".show(this)
                    binding.enabledSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ForegroundRunningService::class.java)
                )
                RemoteRelayCoordinator.syncNow()
            } else {
                RemoteRelayCoordinator.stop()
            }
            refreshStatus()
        }

        binding.syncButton.setOnClickListener {
            RemoteRelayStore.endpoint = binding.endpointInput.text.toString()
            RemoteRelayStore.deviceName = binding.deviceNameInput.text.toString()
            if (!RemoteRelayStore.isConfigured()) {
                "请先完成设备配对".show(this)
                return@setOnClickListener
            }
            RemoteRelayCoordinator.syncNow()
            "同步请求已提交".show(this)
        }

        binding.unbindButton.setOnClickListener {
            RemoteRelayCoordinator.clearBinding()
            binding.endpointInput.setText("")
            binding.pairingCodeInput.setText("")
            binding.enabledSwitch.isChecked = false
            refreshStatus()
            "本机绑定信息已清除，请在云端同步撤销旧设备".show(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val paired = RemoteRelayStore.isConfigured()
        val syncTime = if (RemoteRelayStore.lastSyncAt > 0) {
            dateFormat.format(Date(RemoteRelayStore.lastSyncAt))
        } else {
            "尚未同步"
        }
        val status = buildString {
            appendLine("状态：${if (paired) "已配对" else "未配对"}")
            appendLine("设备 ID：${RemoteRelayStore.deviceId.ifBlank { "无" }}")
            appendLine("配置版本：${RemoteRelayStore.configRevision}")
            appendLine("最后同步：$syncTime")
            if (RemoteRelayStore.lastError.isNotBlank()) {
                append("最后错误：${RemoteRelayStore.lastError}")
            }
        }
        binding.statusView.text = status.trim()
    }
}
