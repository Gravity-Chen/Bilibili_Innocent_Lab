package kpurifyfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HotspotPageResp {
    private final List<ReplyInfo> replies;
    private final List<Object> opinions;
    private final Object page;

    public HotspotPageResp(List<ReplyInfo> replies, List<Object> opinions, Object page) {
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
        this.opinions = Collections.unmodifiableList(new ArrayList<>(opinions));
        this.page = page;
    }

    public List<ReplyInfo> getRepliesList() { return replies; }
    public List<Object> getOpinionsList() { return opinions; }
    public Object getPage() { return page; }
    public static Builder newBuilder(HotspotPageResp prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final List<ReplyInfo> replies;
        private final List<Object> opinions;
        private final Object page;

        Builder(HotspotPageResp prototype) {
            replies = new ArrayList<>(prototype.replies);
            opinions = prototype.opinions;
            page = prototype.page;
        }

        public Builder clearReplies() { replies.clear(); return this; }
        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }
        public HotspotPageResp build() { return new HotspotPageResp(replies, opinions, page); }
    }
}
