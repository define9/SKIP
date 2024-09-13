package com.android.skip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Observer
import com.android.skip.R
import com.android.skip.data.config.ConfigLoadRepository
import com.android.skip.dataclass.AccessibilityNodeInfoCarrier
import com.android.skip.dataclass.NodeChildSchema
import com.android.skip.dataclass.NodeRootSchema
import com.android.skip.ui.alive.notificationbar.NotificationBarRepository
import com.android.skip.ui.main.start.StartAccessibilityRepository
import com.android.skip.util.AccessibilityState
import com.android.skip.util.AppBasicInfoUtils
import com.android.skip.util.MyToast
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ServiceUtils
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject


@AndroidEntryPoint
class MyAccessibilityService : AccessibilityService() {
    private var appActivityName: String? = null
    private var appPackageName: String? = null
    private val clickedRect: MutableSet<String> = mutableSetOf()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    @Inject
    lateinit var repository: StartAccessibilityRepository

    @Inject
    lateinit var accessibilityInspectRepository: AccessibilityInspectRepository

    @Inject
    lateinit var configLoadRepository: ConfigLoadRepository

    @Inject
    lateinit var notificationBarRepository: NotificationBarRepository

    private val observer = Observer<Boolean> { enabled ->
        if (enabled) {
            ServiceUtils.startService(MyForegroundService::class.java)
        } else {
            ServiceUtils.stopService(MyForegroundService::class.java)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val rootNode = getCurrentRootNode()

            val rootNodePackageName = rootNode.packageName.toString()
            if (rootNodePackageName != appPackageName) {
                clickedRect.clear()
                appPackageName = rootNodePackageName
            }

            val that = this
            serviceScope.launch {
                val targetRect = configLoadRepository.getTargetRect(rootNode, appActivityName)
                targetRect?.let { rect ->
                    val rectStr = rect.toString()
                    if (!clickedRect.contains(rectStr)) {
                        click(that, rect)
                        clickedRect.add(rectStr)
                        LogUtils.d("clicked: packageName is $rootNodePackageName rect is $rectStr")
                    }
                }
            }

            getActivityName(event)?.let {
                appActivityName = it
            }

            appActivityName?.let {
                if (accessibilityInspectRepository.isStartCaptureNode) {
                    startCaptureNode(rootNode, it)
                    accessibilityInspectRepository.stopCaptureNode()
                }
            }

        } catch (e: Exception) {
//            LogUtils.e(e)
        }
    }

    private fun click(accessibilityService: AccessibilityService, rect: Rect) {
        val path = Path()
        path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        accessibilityService.dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    MyToast.show(R.string.toast_skip_tip)
                }
            },
            null
        )
    }

    override fun onInterrupt() {
        TODO("Not yet implemented")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository.changeAccessibilityState(AccessibilityState.STARTED)

        notificationBarRepository.enable.observeForever(observer)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        repository.changeAccessibilityState(AccessibilityState.STOPPED)
        notificationBarRepository.enable.removeObserver(observer)

        if (ServiceUtils.isServiceRunning(MyForegroundService::class.java)) {
            ServiceUtils.stopService(MyForegroundService::class.java)
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val isServiceRunning = ServiceUtils.isServiceRunning(InspectService::class.java)
        if (event != null
            && event.action == KeyEvent.ACTION_DOWN
            && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            && isServiceRunning
        ) {
            accessibilityInspectRepository.startAccessibilityInspect()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun getCurrentRootNode(): AccessibilityNodeInfo {
        return rootInActiveWindow ?: throw IllegalStateException("No valid root node available")
    }

    private fun isSystemClass(className: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            val packageName = clazz.getPackage()?.name
            packageName != null && (packageName.startsWith("android") || packageName.startsWith("androidx"))
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun getActivityName(event: AccessibilityEvent?): String? {
        event ?: return null
        val className = event.className

        className ?: return null
        val classNameStr = className.toString()

        val isSystem = isSystemClass(classNameStr)
        return if (!isSystem) classNameStr else null
    }

    private fun startCaptureNode(rootNode: AccessibilityNodeInfo, className: String) {
        var uniqueNodeId = 0
        val queue: MutableList<AccessibilityNodeInfoCarrier> = mutableListOf(
            AccessibilityNodeInfoCarrier(rootNode, 0, -1, uniqueNodeId)
        )
        val nodeChildSchemaList: MutableList<NodeChildSchema> = mutableListOf()

        while (queue.isNotEmpty()) {
            val (node, depth, parentId, nodeId) = queue.removeAt(0)
            processNode(node, nodeChildSchemaList, depth, parentId, nodeId)

            for (i in 0 until node.childCount) {
                uniqueNodeId += 1
                node.getChild(i)?.let {
                    queue.add(
                        AccessibilityNodeInfoCarrier(it, depth + 1, nodeId, uniqueNodeId)
                    )
                }
            }
        }

        val nodeRootSchema = NodeRootSchema(
            accessibilityInspectRepository.fileId,
            AppBasicInfoUtils.getAppName(rootNode.packageName.toString()),
            rootNode.packageName.toString(),
            className,
            ScreenUtils.getScreenHeight(),
            ScreenUtils.getScreenWidth(),
            System.currentTimeMillis(),
            nodeChildSchemaList
        )

        val gson = Gson()
        val jsonStr = gson.toJson(nodeRootSchema)
        val file = File(
            accessibilityInspectRepository.filepath,
            "${accessibilityInspectRepository.fileId}.json"
        )
        file.writeText(jsonStr)
    }

    private fun processNode(
        node: AccessibilityNodeInfo,
        nodeChildSchemaList: MutableList<NodeChildSchema>,
        depth: Int,
        parentId: Int,
        nodeId: Int
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val myNodeChild = NodeChildSchema(
            depth,
            node.childCount,
            parentId,
            nodeId,
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            node.isClickable
        )

        node.className?.let {
            myNodeChild.className = it.toString()
        }

        node.text?.let {
            myNodeChild.text = it.toString()
        }

        node.viewIdResourceName?.let {
            myNodeChild.viewIdResourceName = it
        }

        nodeChildSchemaList.add(myNodeChild)
    }
}