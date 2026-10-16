package io.dataease.extensions.datasource.dto;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Host-installed SQL policy; extension SDK remains independent of the application. */
public final class QueryScope {
    private static volatile UnaryOperator<String> policy = UnaryOperator.identity();
    private QueryScope() { }
    public static void install(UnaryOperator<String> value) { policy = Objects.requireNonNull(value); }
    public static String apply(String sql) { return policy.apply(sql); }
}
