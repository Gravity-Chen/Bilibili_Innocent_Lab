package com.bilibili.lib.blconfig.internal;

import com.bilibili.lib.blconfig.Contract;

/** 真实泛型桥接样本：javac 生成 get(String,Object):Object 的 bridge。 */
public final class OverrideConfig implements Contract<String> {
    public int reads;
    @Override public String get(String key, String defaultValue) {
        reads++;
        return defaultValue;
    }
}
