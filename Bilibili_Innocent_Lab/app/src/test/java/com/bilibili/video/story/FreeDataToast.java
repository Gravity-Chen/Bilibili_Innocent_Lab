package com.bilibili.video.story;

public final class FreeDataToast {
    private final String title;
    private final String subtitle;
    private final String buttonText;
    private final String uri;
    private final long tipsId;
    private boolean brokenReads;
    public int reads;

    public FreeDataToast(String title) {
        this(title, "", "", "", 0);
    }

    public FreeDataToast(String title, String subtitle, String buttonText, String uri, long tipsId) {
        this.title = title;
        this.subtitle = subtitle;
        this.buttonText = buttonText;
        this.uri = uri;
        this.tipsId = tipsId;
    }

    public void setBrokenReads(boolean brokenReads) {
        this.brokenReads = brokenReads;
    }

    private void recordRead() {
        reads++;
        if (brokenReads) throw new IllegalStateException("simulated Story promotion field read failure");
    }

    public String getTitle() {
        recordRead();
        return title;
    }

    public String getSubtitle() {
        recordRead();
        return subtitle;
    }

    public String getButtonText() {
        recordRead();
        return buttonText;
    }

    public String getUri() {
        recordRead();
        return uri;
    }

    public long getTipsId() {
        recordRead();
        return tipsId;
    }
}
