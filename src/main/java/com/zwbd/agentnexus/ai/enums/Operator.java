package com.zwbd.agentnexus.ai.enums;

/**
 * @Author: wnli
 * @Date: 2025/9/15 17:49
 * @Desc:
 */
public enum Operator {
    EQUALS,             // 等于 (value: String/Number/Boolean)
    NOT_EQUALS,         // 不等于
    IN,                 // 包含 (value: List)
    NOT_IN,             // 不包含
    GREATER_THAN,       // 大于
    GREATER_THAN_EQUALS,
    LESS_THAN,          // 小于
    LESS_THAN_EQUALS

    ;

    // 操作符映射
    public static String getOperatorSymbol(Operator operator) {
        return switch (operator) {
            case EQUALS -> "==";
            case NOT_EQUALS -> "!=";
            case IN -> "in";
            case NOT_IN -> "not in";
            case GREATER_THAN -> ">";
            case GREATER_THAN_EQUALS -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_EQUALS -> "<=";
        };
    }
}
