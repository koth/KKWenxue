package com.kkwenxue.shanye.data

enum class ChineseScript {
    SIMPLIFIED,
    TRADITIONAL;

    fun opposite() = if (this == SIMPLIFIED) TRADITIONAL else SIMPLIFIED
}

/** ICU's rule based Chinese conversion. Display conversion keeps UTF-16 offsets stable. */
object ChineseScriptConverter {
    private data class RuntimeEngine(val simplifiedToTraditional: Any, val traditionalToSimplified: Any, val method: java.lang.reflect.Method)
    private val runtimeEngine by lazy {
        runCatching {
            // android.icu is available on newer Android releases. Reflection keeps the
            // reader installable on API 26-28, where this public API is unavailable.
            val type = Class.forName("android.icu.text.Transliterator")
            val factory = type.getMethod("getInstance", String::class.java)
            val method = type.getMethod("transliterate", String::class.java)
            RuntimeEngine(factory.invoke(null, "Simplified-Traditional"), factory.invoke(null, "Traditional-Simplified"), method)
        }.getOrNull()
    }

    private val fallbackSimplifiedToTraditional = mapOf(
        '这' to '這', '个' to '個', '测' to '測', '试' to '試', '里' to '裡', '发' to '發', '现' to '現',
        '后' to '後', '来' to '來', '时' to '時', '间' to '間', '书' to '書', '说' to '說', '话' to '話',
        '读' to '讀', '写' to '寫', '见' to '見', '听' to '聽', '问' to '問', '开' to '開', '关' to '關',
        '门' to '門', '国' to '國', '学' to '學', '习' to '習', '体' to '體', '现' to '現', '数' to '數',
        '据' to '據', '过' to '過', '还' to '還', '这' to '這', '个' to '個', '两' to '兩', '无' to '無',
        '头' to '頭', '长' to '長', '动' to '動', '静' to '靜', '让' to '讓', '从' to '從', '东' to '東',
        '来' to '來', '电' to '電', '话' to '話', '网' to '網', '页' to '頁', '线' to '線', '经' to '經',
        '过' to '過', '进' to '進', '出' to '出', '声' to '聲', '种' to '種', '数' to '數', '万' to '萬',
        '专' to '專', '业' to '業', '医' to '醫', '药' to '藥', '杀' to '殺', '伤' to '傷', '难' to '難',
        '归' to '歸', '类' to '類', '实' to '實', '际' to '際', '华' to '華', '岁' to '歲', '岁' to '歲',
        '处' to '處', '场' to '場', '气' to '氣', '风' to '風', '云' to '雲', '雨' to '雨', '听' to '聽',
        '欢' to '歡', '乐' to '樂', '忆' to '憶', '梦' to '夢', '惊' to '驚', '险' to '險', '敌' to '敵',
        '义' to '義', '礼' to '禮', '务' to '務', '将' to '將', '军' to '軍', '阵' to '陣', '观' to '觀',
        '觉' to '覺', '现' to '現', '应' to '應', '认' to '認', '识' to '識', '记' to '記', '忆' to '憶',
        '级' to '級', '结' to '結', '续' to '續', '终' to '終', '读' to '讀', '译' to '譯', '简' to '簡',
        '繁' to '繁', '汉' to '漢', '语' to '語', '词' to '詞', '义' to '義', '题' to '題', '页' to '頁'
    )
    private val fallbackTraditionalToSimplified = fallbackSimplifiedToTraditional.entries.associate { (simple, traditional) -> traditional to simple }

    fun convert(text: String, script: ChineseScript): String {
        val engine = runtimeEngine
        if (engine != null) {
            val transformer = if (script == ChineseScript.SIMPLIFIED) engine.traditionalToSimplified else engine.simplifiedToTraditional
            return engine.method.invoke(transformer, text) as String
        }
        val map = if (script == ChineseScript.SIMPLIFIED) fallbackTraditionalToSimplified else fallbackSimplifiedToTraditional
        return text.map { map[it] ?: it }.joinToString("")
    }

    fun detect(text: String): ChineseScript {
        val sample = text.take(8_000)
        val simplifiedChanges = differenceCount(sample, convert(sample, ChineseScript.SIMPLIFIED))
        val traditionalChanges = differenceCount(sample, convert(sample, ChineseScript.TRADITIONAL))
        return if (simplifiedChanges > traditionalChanges) ChineseScript.TRADITIONAL else ChineseScript.SIMPLIFIED
    }

    private fun differenceCount(left: String, right: String): Int {
        val common = minOf(left.length, right.length)
        var changes = kotlin.math.abs(left.length - right.length)
        for (index in 0 until common) if (left[index] != right[index]) changes++
        return changes
    }
}
