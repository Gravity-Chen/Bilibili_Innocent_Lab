package kpurifyfixture;

import java.util.Collections;

public final class ReplyInfoReply {
    private static final ReplyInfo EMPTY_REPLY = new ReplyInfo(new Content(Collections.emptyMap()));
    private static final ReplyInfoReply DEFAULT_INSTANCE = new ReplyInfoReply(null);
    private final ReplyInfo reply;

    public ReplyInfoReply(ReplyInfo reply) { this.reply = reply; }
    public boolean hasReply() { return reply != null; }
    public ReplyInfo getReply() { return reply == null ? EMPTY_REPLY : reply; }
    public static ReplyInfoReply getDefaultInstance() { return DEFAULT_INSTANCE; }
    public static Builder newBuilder(ReplyInfoReply prototype) { return new Builder(prototype); }

    public static final class Builder {
        private ReplyInfo reply;
        Builder(ReplyInfoReply prototype) { reply = prototype.reply; }
        public Builder setReply(ReplyInfo value) { reply = value; return this; }
        public Builder clearReply() { reply = null; return this; }
        public ReplyInfoReply build() { return new ReplyInfoReply(reply); }
    }
}
