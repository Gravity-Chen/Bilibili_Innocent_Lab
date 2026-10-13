package com.bilibili.lib.blconfig.internal;

import com.bilibili.lib.blconfig.Contract;

public final class TypedContract implements Contract<Object> {
    public int reads;
    @Override public Object get(String key, Object defaultValue) {
        reads++;
        return defaultValue;
    }
}
