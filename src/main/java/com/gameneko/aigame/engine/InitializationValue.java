package com.gameneko.aigame.engine;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 初始化 schema 与结构化行动参数接受的有限类型数据。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InitializationValue.Text.class, name = "text"),
        @JsonSubTypes.Type(value = InitializationValue.IntegerNumber.class, name = "integer"),
        @JsonSubTypes.Type(value = InitializationValue.DecimalNumber.class, name = "decimal"),
        @JsonSubTypes.Type(value = InitializationValue.Flag.class, name = "flag")
})
public sealed interface InitializationValue permits InitializationValue.Text, InitializationValue.IntegerNumber,
        InitializationValue.DecimalNumber, InitializationValue.Flag {
    record Text(String value) implements InitializationValue {
        public Text { if (value == null) throw new IllegalArgumentException("Text value is required"); }
    }

    record IntegerNumber(long value) implements InitializationValue {}

    record DecimalNumber(double value) implements InitializationValue {
        public DecimalNumber { if (!Double.isFinite(value)) throw new IllegalArgumentException("Decimal value must be finite"); }
    }

    record Flag(boolean value) implements InitializationValue {}
}
