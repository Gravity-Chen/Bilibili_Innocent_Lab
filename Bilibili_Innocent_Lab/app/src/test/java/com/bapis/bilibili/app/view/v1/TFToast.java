package com.bapis.bilibili.app.view.v1;

/** TF 提示只以标题判定展示；保留不可变原字段与默认单例。 */
public final class TFToast {
    private static final TFToast DEFAULT_INSTANCE = new TFToast("");
    private final String title;
    private final boolean broken;

    public TFToast(String title) { this(title, false); }
    public TFToast(String title, boolean broken) { this.title = title; this.broken = broken; }
    public String getTitle() {
        if (broken) throw new IllegalStateException("fixture TF title read failure");
        return title;
    }
    public static TFToast getDefaultInstance() { return DEFAULT_INSTANCE; }
}
