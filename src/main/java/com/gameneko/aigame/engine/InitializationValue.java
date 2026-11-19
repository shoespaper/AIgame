package com.gameneko.aigame.engine;

/** Bounded, typed data accepted by initialization schemas and structured intent arguments. */
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
