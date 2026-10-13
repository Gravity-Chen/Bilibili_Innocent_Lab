package com.bilibili.app.comment3.ui.holder.handle;

import android.view.View;

/** New action handlers capture action state, rather than the whole CommentItem. */
public final class ReplyButtonTestHandler {
    public static final class Binding { public View getRoot() { return null; } }
    public void bind(Binding binding) {}
    public void reset(Binding binding, boolean expanded) {}
    public void action(String message) {}
}
