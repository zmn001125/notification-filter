package com.local.notificationfilter.core.model

/**
 * V13.0 规则分组：纯函数集合（无 Android 依赖，便于 JVM 单测）。
 *
 * 分组是**归类标签**，不是匹配条件：一条规则可同时属于多个分组，
 * 列表页顶部 tab 用它过滤显示。引擎、JSON 迁移、导入导出都不感知它。
 */
object RuleGroups {

    /** 组名最大长度（UI 输入同样按此截断，避免超长组名撑坏 tab）。 */
    const val MAX_NAME_LENGTH = 20

    /**
     * 规范化一组组名输入：去空白 → 丢空 → 去重（保持原顺序）。
     * 保存规则前调用，保证库里不会存进 `""` 或重复项。
     */
    fun normalize(names: List<String>): List<String> =
        names.map { it.trim().take(MAX_NAME_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 这批规则里出现过的全部分组名（规范化后去重 + 字典序，tab 顺序稳定）。 */
    fun distinctOf(rules: List<Rule>): List<String> =
        rules.flatMap { it.groups }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    /**
     * 按分组过滤规则；[group] 为 null 或空白 = 不过滤（全部）。
     * 判定用 trim 后的严格相等，不做模糊匹配。
     */
    fun filter(rules: List<Rule>, group: String?): List<Rule> {
        val g = group?.trim().orEmpty()
        if (g.isEmpty()) return rules
        return rules.filter { g in it.groups }
    }
}
