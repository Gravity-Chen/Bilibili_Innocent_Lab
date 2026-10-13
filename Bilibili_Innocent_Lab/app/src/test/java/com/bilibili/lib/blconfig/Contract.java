package com.bilibili.lib.blconfig;

/** 泛型配置接口；具体实现可通过 javac bridge 暴露擦除后的 ABI。 */
public interface Contract<T> {
    T get(String key, T defaultValue);
}
