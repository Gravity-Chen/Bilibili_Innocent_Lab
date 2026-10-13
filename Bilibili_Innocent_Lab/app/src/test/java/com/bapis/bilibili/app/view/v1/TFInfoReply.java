package com.bapis.bilibili.app.view.v1;

/** 推广 toast 与免流状态/面板是独立字段；Hook 只替换 getter 的返回值。 */
public final class TFInfoReply {
    private static final TFInfoReply DEFAULT_INSTANCE = new TFInfoReply(TFToast.getDefaultInstance());
    private final TFToast toast;
    private final int userFlagNew;
    private final int tipsId;
    private final Object panel;

    public TFInfoReply(TFToast toast) { this(toast, 0, 0, null); }
    public TFInfoReply(TFToast toast, int userFlagNew, int tipsId, Object panel) {
        this.toast = toast; this.userFlagNew = userFlagNew; this.tipsId = tipsId; this.panel = panel;
    }
    public TFToast getTfToast() { return toast; }
    public int getUserFlagNew() { return userFlagNew; }
    public int getTipsId() { return tipsId; }
    public Object getPanel() { return panel; }
    public static TFInfoReply getDefaultInstance() { return DEFAULT_INSTANCE; }
}
