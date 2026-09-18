package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 电话控制 Skill
 *
 * 职责：拨打电话（支持联系人姓名和电话号码）
 *
 * 语音指令：
 * - "给张三打电话"
 * - "打电话给张三"
 * - "呼叫张三"
 * - "拨打10086"
 * - "拨打电话10086"
 *
 * 依赖：Context + 联系人读取权限 + 拨打电话权限
 */
class PhoneSkill(private val context: Context) {

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "phone.call" -> callPhone(
            intent.params["name"] ?: intent.params["number"] ?: ""
        )
        else -> ExecutionResult(false, "不支持的电话指令")
    }

    /**
     * 拨打电话。
     * 如果目标是纯数字，直接拨打；否则从联系人中查找。
     */
    private fun callPhone(target: String): ExecutionResult {
        val clean = target.trim()
        if (clean.isEmpty()) {
            return ExecutionResult(false, "没听清要打给谁")
        }

        // 1. 如果是纯数字（电话号码），直接拨打
        if (isPhoneNumber(clean)) {
            return makeCall(clean, clean)
        }

        // 2. 否则从联系人中查找
        val contact = findContact(clean)
        if (contact != null) {
            return makeCall(contact.first, contact.second)
        }

        return ExecutionResult(false, "未找到联系人「$clean」，请说拨打加电话号码")
    }

    /**
     * 判断是否是电话号码（纯数字，可包含 - 和空格）
     */
    private fun isPhoneNumber(text: String): Boolean {
        val digits = text.replace(Regex("[\\s-]"), "")
        return digits.matches(Regex("\\d{3,15}"))
    }

    /**
     * 从联系人中查找（模糊匹配姓名）。
     * 返回 Pair(姓名, 电话号码)，找不到返回 null。
     */
    private fun findContact(name: String): Pair<String, String>? {
        return try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            // 模糊查询：姓名包含关键词
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            val cursor: Cursor? = contentResolver.query(
                uri, projection, selection, selectionArgs, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val numberIndex = it.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    val contactName = if (nameIndex >= 0) it.getString(nameIndex) ?: name else name
                    val phoneNumber = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    if (phoneNumber.isNotEmpty()) {
                        return Pair(contactName, phoneNumber)
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            android.util.Log.w("PhoneSkill", "读取联系人权限被拒绝: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.w("PhoneSkill", "查找联系人失败: ${e.message}")
            null
        }
    }

    /**
     * 直接拨打电话。
     * 使用 ACTION_CALL 直接拨打（需要 CALL_PHONE 权限）。
     * 如果权限被拒绝，回退到 ACTION_DIAL（打开拨号界面，需要用户手动点击拨打）。
     */
    private fun makeCall(displayName: String, number: String): ExecutionResult {
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(callIntent)
            android.util.Log.d("PhoneSkill", "正在拨打: $displayName ($number)")
            ExecutionResult(true, "正在拨打$displayName")
        } catch (e: SecurityException) {
            // 没有 CALL_PHONE 权限，回退到打开拨号界面
            android.util.Log.w("PhoneSkill", "没有拨打电话权限，回退到拨号界面: ${e.message}")
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dialIntent)
                ExecutionResult(true, "已打开拨号界面，请手动拨打$displayName")
            } catch (e2: Exception) {
                ExecutionResult(false, "拨打电话失败：${e2.message ?: "未知错误"}")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "拨打电话失败：${e.message ?: "未知错误"}")
        }
    }
}
