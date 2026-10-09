package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.databinding.DialogWorkWeekTypeBinding
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.WorkWeekType
import com.pengxh.daily.app.utils.WorkdayMode
import com.pengxh.daily.app.utils.WorkdaySchedule
import com.pengxh.daily.app.utils.WorkdayScheduleResolver
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.isNumber
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourArray = arrayListOf("0", "1", "2", "3", "4", "5", "6", "自定义（单位：时）")
    private val timeArray = arrayListOf("15", "30", "45", "自定义（单位：秒）")
    private val optionArray = arrayListOf("QQ", "微信", "TIM", "支付宝", "剪切板")
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

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
        val hour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeView.text = "每天${hour}点"

        val time = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeoutTextView.text = "${time}s"

        binding.keyTextView.text = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")

        updateWorkdaySummary(CustomWorkdayManager.loadSchedule())

        binding.autoTaskSwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)

        binding.skipHolidaySwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)

        val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        binding.randomTimeSwitch.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeLayout.visibility = View.VISIBLE
            val value = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            binding.minuteRangeView.text = "${value}分钟"
        } else {
            binding.minuteRangeLayout.visibility = View.GONE
        }
    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(hourArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setHourByPosition(position)
                    }
                }).build().show()
        }

        binding.timeoutLayout.setOnClickListener {
            BottomActionSheet.Builder()
                .setContext(this)
                .setActionItemTitle(timeArray)
                .setItemTextColor(R.color.theme_color.convertColor(this))
                .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                    override fun onActionItemClick(position: Int) {
                        setTimeByPosition(position)
                    }
                }).build().show()
        }

        binding.keyLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置打卡口令")
                .setHintMessage("请输入打卡口令，如：打卡")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        SaveKeyValues.saveString(Constant.REMOTE_COMMAND_KEY, value)
                        binding.keyTextView.text = value
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.workdayLayout.setOnClickListener {
            showWorkdayModeSelector()
        }

        binding.randomTimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeLayout.visibility = View.VISIBLE
                val value =
                    SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                binding.minuteRangeView.text = "${value}分钟"
            } else {
                binding.minuteRangeLayout.visibility = View.GONE
            }
        }

        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, isChecked)
        }

        binding.minuteRangeLayout.setOnClickListener {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置随机时间范围")
                .setHintMessage("请输入整数，如：30")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateRandomMinuteRange(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        }

        binding.exportLayout.setOnClickListener {
            val exportData = ExportDataModel()

            // Int
            exportData.resetTime =
                SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
            exportData.overtime =
                SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
            exportData.timeRange =
                SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            exportData.msgChannel =
                SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
            exportData.targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)

            // String
            exportData.remoteCommand = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
            exportData.msgTitle =
                SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
            exportData.wxKey = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
            val workdaySchedule = CustomWorkdayManager.loadSchedule()
            exportData.customWorkdays = CustomWorkdayManager.serializeWorkdays(
                CustomWorkdayManager.getLegacyExportWorkdays(workdaySchedule)
            )
            exportData.workdayMode = workdaySchedule.mode.name
            exportData.smallWeekWorkdays = CustomWorkdayManager.serializeWorkdays(
                workdaySchedule.smallWeekWorkdays
            )
            exportData.bigWeekWorkdays = CustomWorkdayManager.serializeWorkdays(
                workdaySchedule.bigWeekWorkdays
            )
            exportData.bigWeekAnchorMonday = workdaySchedule.bigWeekAnchorMonday?.toString().orEmpty()

            // Boolean
            exportData.isDetectGesture =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            exportData.isBackToHome = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            exportData.isAutoRecycle =
                SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
            exportData.isRandomTime = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
            exportData.isSkipHoliday = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
            exportData.isSavePower =
                SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)

            // EmailConfig
            val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
            if (!obj.isEmpty) {
                val outbox = obj.get("outbox").asString
                val authCode = obj.get("authCode").asString
                val inbox = obj.get("inbox").asString
                exportData.emailConfig = Triple(outbox, authCode, inbox)
            }

            // TaskBeans
            lifecycleScope.launch {
                val taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                if (taskBeans.isNotEmpty()) {
                    exportData.tasks = taskBeans
                } else {
                    exportData.tasks = ArrayList<DailyTaskBean>()
                }

                val json = exportData.toJson()
                Log.d(kTag, json)

                // 分享
                BottomActionSheet.Builder()
                    .setContext(this@TaskConfigActivity)
                    .setActionItemTitle(optionArray)
                    .setItemTextColor(R.color.theme_color.convertColor(this@TaskConfigActivity))
                    .setOnActionSheetListener(object : BottomActionSheet.OnActionSheetListener {
                        override fun onActionItemClick(position: Int) {
                            when (position) {
                                0 -> shareTextTo(Constant.QQ, "QQ", json)
                                1 -> shareTextTo(Constant.WECHAT, "微信", json)
                                2 -> shareTextTo(Constant.TIM, "TIM", json)
                                3 -> shareTextTo(Constant.ZFB, "支付宝", json)
                                4 -> {
                                    val cipData = ClipData.newPlainText("TaskConfig", json)
                                    clipboard.setPrimaryClip(cipData)
                                    "已复制到剪切板".show(context)
                                }
                            }
                        }
                    }).build().show()
            }
        }
    }

    private fun showWorkdayModeSelector() {
        val schedule = CustomWorkdayManager.loadSchedule()
        val selectedIndex = if (schedule.mode == WorkdayMode.ALTERNATING) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("工作日模式")
            .setSingleChoiceItems(
                arrayOf("每周固定", "大小周"), selectedIndex
            ) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    showFixedWorkdaySelector(schedule)
                } else {
                    showSmallWeekSelector(schedule)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFixedWorkdaySelector(schedule: WorkdaySchedule) {
        showWeekdayMultiSelector("选择每周工作日", schedule.fixedWorkdays) { selectedDays ->
            val updated = schedule.copy(
                mode = WorkdayMode.FIXED, fixedWorkdays = selectedDays
            )
            CustomWorkdayManager.saveSchedule(updated)
            updateWorkdaySummary(updated)
        }
    }

    private fun showSmallWeekSelector(schedule: WorkdaySchedule) {
        val defaults = CustomWorkdayManager.createAlternatingDefaults(schedule)
        showWeekdayMultiSelector("选择小周工作日", defaults.smallWeekWorkdays) { selectedDays ->
            showBigWeekSelector(defaults.copy(smallWeekWorkdays = selectedDays))
        }
    }

    private fun showBigWeekSelector(schedule: WorkdaySchedule) {
        showWeekdayMultiSelector("选择大周工作日", schedule.bigWeekWorkdays) { selectedDays ->
            showCurrentWeekTypeSelector(schedule.copy(bigWeekWorkdays = selectedDays))
        }
    }

    private fun showCurrentWeekTypeSelector(schedule: WorkdaySchedule) {
        val currentWeekType = WorkdayScheduleResolver.resolveWeekType(
            schedule, LocalDate.now()
        ) ?: WorkWeekType.SMALL
        val dialogBinding = DialogWorkWeekTypeBinding.inflate(layoutInflater)
        if (currentWeekType == WorkWeekType.BIG) {
            dialogBinding.bigWeekRadioButton.isChecked = true
        } else {
            dialogBinding.smallWeekRadioButton.isChecked = true
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("选择本周类型")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val selectedWeekType = if (dialogBinding.bigWeekRadioButton.isChecked) {
                    WorkWeekType.BIG
                } else {
                    WorkWeekType.SMALL
                }
                val updated = schedule.copy(
                    mode = WorkdayMode.ALTERNATING,
                    bigWeekAnchorMonday = CustomWorkdayManager.createBigWeekAnchor(
                        selectedWeekType
                    )
                )
                CustomWorkdayManager.saveSchedule(updated)
                updateWorkdaySummary(updated)
            }
            .show()
    }

    private fun showWeekdayMultiSelector(
        title: String,
        initialDays: Set<DayOfWeek>,
        onConfirm: (Set<DayOfWeek>) -> Unit
    ) {
        val orderedDays = CustomWorkdayManager.getOrderedDays()
        val selectedDays = initialDays.toMutableSet()
        val labels = orderedDays.map { CustomWorkdayManager.getDayLabel(it) }.toTypedArray()
        val checkedItems = orderedDays.map { it in selectedDays }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val day = orderedDays[which]
                if (isChecked) {
                    selectedDays.add(day)
                } else {
                    selectedDays.remove(day)
                }
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                if (selectedDays.isEmpty()) {
                    "至少保留一天为工作日".show(this)
                    return@setPositiveButton
                }

                val normalized = orderedDays.filter { it in selectedDays }.toSet()
                onConfirm(normalized)
            }
            .show()
    }

    private fun updateWorkdaySummary(schedule: WorkdaySchedule) {
        binding.workdayValueView.text = CustomWorkdayManager.formatSchedule(schedule)
    }

    private fun setHourByPosition(position: Int) {
        if (position == hourArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置重置时间")
                .setHintMessage("直接输入整数时间即可，如：6")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateResetHour(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            updateResetHour(hourArray[position].toInt())
        }
    }

    private fun updateResetHour(hour: Int) {
        if (hour !in 0..23) {
            "重置时间必须在0到23点之间".show(this)
            return
        }
        binding.resetTimeView.text = "每天${hour}点"
        setTaskResetTime(hour)
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, hour)
        // 通知 Service 更新倒计时显示
        ForegroundRunningService.emitResetTaskTime()
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeArray.size - 1) {
            AlertInputDialog.Builder()
                .setContext(this)
                .setTitle("设置超时时间")
                .setHintMessage("直接输入整数时间即可，如：60")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .setOnDialogButtonClickListener(object :
                    AlertInputDialog.OnDialogButtonClickListener {
                    override fun onConfirmClick(value: String) {
                        if (value.isNumber()) {
                            updateTimeout(value.toInt())
                        } else {
                            "直接输入整数时间即可".show(context)
                        }
                    }

                    override fun onCancelClick() {}
                }).build().show()
        } else {
            updateTimeout(timeArray[position].toInt())
        }
    }

    private fun updateTimeout(time: Int) {
        if (time <= 0) {
            "超时时间必须大于0秒".show(this)
            return
        }
        binding.timeoutTextView.text = "${time}s"
        SaveKeyValues.saveInt(Constant.STAY_OVERTIME_KEY, time)
        FloatingWindowController.setOvertime(time)
    }

    private fun shareTextTo(packageName: String, appName: String, text: String) {
        if (!isApplicationExist(packageName)) {
            "请先安装${appName}".show(this)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            "分享失败".show(this)
        }
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间范围不能小于0分钟".show(this)
            return
        }
        binding.minuteRangeView.text = "${value}分钟"
        SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, value)
    }
}
