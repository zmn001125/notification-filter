package com.local.notificationfilter.core.model

/**
 * 字段引用（V2 需求②④，架构 §2.2）：规则的「被选字段」从封闭的六文本字段扩展为
 * **文本字段 + 通知元信息 + 任意 extras 键** 三类。
 *
 * id 规范（共享约定 2，`FieldRef.id` 是唯一序列化口径）：
 * - 文本字段 = 枚举名（`TITLE`…）；
 * - 元信息 = `meta:<KIND>`（如 `meta:KEY`）；
 * - extras = `extras:<原键名>`（如 `extras:filterbox.field.CHANNEL_ID`，键名大小写敏感）。
 *
 * 该 id 同时用于流水 `hitFieldsJson` 存储：旧流水的 `["TITLE","TEXT"]` 无需转换
 * （[fromId] 对旧枚举名与坏数据都兜底为 [FieldRef.Extras]，不抛异常）。
 */
sealed class FieldRef {

    abstract val id: String

    abstract val label: String

    abstract val group: FieldGroup

    /** 文本字段（V1 六字段，[MatchField]）。 */
    data class Text(val field: MatchField) : FieldRef() {
        // 注意：用初始化器而非 getter——`field` 在属性访问器内是保留字（指幕后字段），
        // 在初始化器内才是构造函数属性。
        override val id: String = field.name
        override val label: String = field.label
        override val group: FieldGroup = FieldGroup.TEXT
    }

    /** 通知元信息（[MetaFieldKind]）。 */
    data class Meta(val kind: MetaFieldKind) : FieldRef() {
        override val id: String = "meta:${kind.name}"
        override val label: String = kind.label
        override val group: FieldGroup = FieldGroup.META
    }

    /**
     * extras 任意键（含厂商私有 / 深层键名，如 `filterbox.field.CHANNEL_ID`）。
     * 键名大小写敏感，取值时对 [NotificationSnapshot.extras] 精确匹配。
     */
    data class Extras(val key: String) : FieldRef() {
        override val id: String get() = "extras:$key"
        override val label: String get() = key
        override val group: FieldGroup get() = FieldGroup.EXTRAS
    }

    companion object {
        /**
         * id → FieldRef（读旧流水 `hitFieldsJson` / 规则回显用）。
         * 未知 id 一律兜底为 [Extras]（原样展示，绝不抛异常）。
         */
        fun fromId(id: String): FieldRef = when {
            id.startsWith("meta:") -> {
                val kind = runCatching { MetaFieldKind.valueOf(id.removePrefix("meta:")) }.getOrNull()
                if (kind != null) Meta(kind) else Extras(id)
            }

            id.startsWith("extras:") -> Extras(id.removePrefix("extras:"))
            else -> runCatching { Text(MatchField.valueOf(id)) }.getOrElse { Extras(id) }
        }
    }
}

/** 字段分组（选择器分组 / 展示用）。 */
enum class FieldGroup(val label: String) {
    TEXT("常用字段"),
    META("通知信息"),
    EXTRAS("Extras 字段"),
}
