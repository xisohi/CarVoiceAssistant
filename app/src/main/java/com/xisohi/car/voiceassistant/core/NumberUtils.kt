package com.xisohi.car.voiceassistant.core

/**
 * 中文数字工具。
 *
 * 语音识别结果中的数字几乎都是汉字（"百分之五十"、"二十五度"、"四十二度"），
 * 而 intents.json 正则与各 Skill 的解析只支持阿拉伯数字（\d）——这是
 * "声音调到百分之五十"等指令稳定识别成功却未匹配意图的根因。
 * 本工具提供汉字数字 → 阿拉伯数字转换，供正则槽位解析后统一使用。
 */
object NumberUtils {

    private val DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /**
     * 汉字数字转 Int（支持 0~999，含"十/百"组合）。
     * 例：五→5，五十→50，二十五→25，十五→15，百→100，两百→200，一百零五→105。
     *
     * 注意：不含"百分之"前缀和"度"等单位——这些由调用方剥离后再传入
     * （如 VolumeSkill 先 replace("百分之","")，CarControlSkill 先 replace("度","")）。
     * 非法输入（含非数字汉字、阿拉伯数字混入）返回 null。
     */
    fun chineseToInt(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0
        var section = 0
        for (c in s) {
            when (c) {
                in DIGITS -> {
                    val d = DIGITS[c]!!
                    if (d == 0) continue  // "零"仅占位（"一百零五"），无实值
                    section = d
                }
                '十' -> {
                    total += (if (section == 0) 1 else section) * 10
                    section = 0
                }
                '百' -> {
                    total += (if (section == 0) 1 else section) * 100
                    section = 0
                }
                else -> return null
            }
        }
        total += section
        return total
    }
}
