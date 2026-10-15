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

    /**
     * 联系人查找结果：把"没找到"和"没权限"两种失败区分开。
     *
     * 修复前 findContact() 一律返回 null，上层只能播报"未找到联系人「X」"——
     * 明明是用户没授权读通讯录，却被说成联系人不存在，用户反复重试、甚至去删了重加联系人也没用。
     *
     * 用 sealed 结果类型而不是"私有字段记录失败原因"：编译器强制 when 穷举所有分支，
     * 不会出现"新增一种失败却忘了处理"；也完全不引入可变状态（无跨调用残留的风险）。
     */
    private sealed interface ContactLookup {
        data class Found(val displayName: String, val number: String) : ContactLookup
        object NotFound : ContactLookup
        object NoPermission : ContactLookup
    }

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
        return when (val lookup = findContact(clean)) {
            is ContactLookup.Found -> makeCall(lookup.displayName, lookup.number)
            // ★ 权限被拒时说清是权限问题，不要误导成"联系人不存在"
            ContactLookup.NoPermission ->
                ExecutionResult(false, "没有读取通讯录的权限，请在设置里授权后再试")
            ContactLookup.NotFound ->
                ExecutionResult(false, "未找到联系人「$clean」，请说拨打加电话号码")
        }
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
     * 返回 [ContactLookup]：找到 / 没找到 / 没权限三种结果。
     */
    private fun findContact(name: String): ContactLookup {
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
            // 列下标提到循环外：每行都查一次没必要（Cursor 下标在本查询内固定）
            cursor?.use {
                val nameIndex = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                // ★ 审计修复：原来只 `if (it.moveToFirst())` 看第一行——首行 NUMBER 为空
                //（同一联系人多个号码时的空号码行、SIM 卡行、仅存 email 的行都很常见）
                // 就直接返回 null，后面明明有号码的行全被丢掉，表现为"联系人明明存在却说未找到"。
                // 改成遍历所有行，返回第一个号码非空的行；游标为空时 while 一次都不进，
                // 自然覆盖原来"查不到就返回 null"的情况。
                // moveToNext() 在刚 query 出来的游标上就等价于 moveToFirst()，不需要先 moveToFirst。
                while (it.moveToNext()) {
                    val number = if (numberIndex >= 0) it.getString(numberIndex).orEmpty() else ""
                    if (number.isNotBlank()) {
                        // 姓名列缺失/为空时退回用查询词当显示名（沿用原逻辑）
                        val contactName = if (nameIndex >= 0) {
                            it.getString(nameIndex)?.takeIf { n -> n.isNotBlank() } ?: name
                        } else {
                            name
                        }
                        // cursor?.use 是 inline，非局部 return 安全（它会正常关闭游标）
                        return ContactLookup.Found(contactName, number)
                    }
                }
            }
            ContactLookup.NotFound
        } catch (e: SecurityException) {
            android.util.Log.w("PhoneSkill", "读取联系人权限被拒绝: ${e.message}")
            ContactLookup.NoPermission
        } catch (e: Exception) {
            android.util.Log.w("PhoneSkill", "查找联系人失败: ${e.message}")
            ContactLookup.NotFound
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
