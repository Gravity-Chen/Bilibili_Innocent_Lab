package com.bilibili.bplus.followinglist.module.item;

/** Arbitrary JVM viewTypes exercise lookup from the official enum; no production numeric literals. */
public enum ModuleEnum {
    Author(71), Desc(112), OpusDesc(203), OpusDescList(304), Archive(505), Footer(919);
    private final int type;
    ModuleEnum(int type) { this.type = type; }
    public int viewType() { return type; }
}
