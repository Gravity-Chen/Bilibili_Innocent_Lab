package kpurifyfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FoldListResp {
    private final List<ReplyInfo> replies;
    private final SubjectControl subjectControl;
    private final Object paginationReply;

    public FoldListResp(List<ReplyInfo> replies, SubjectControl subjectControl, Object paginationReply) {
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
        this.subjectControl = subjectControl;
        this.paginationReply = paginationReply;
    }

    public List<ReplyInfo> getRepliesList() { return replies; }
    public boolean hasSubjectControl() { return subjectControl != null; }
    public SubjectControl getSubjectControl() { return subjectControl; }
    public Object getPaginationReply() { return paginationReply; }
    public static Builder newBuilder(FoldListResp prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final List<ReplyInfo> replies;
        private SubjectControl subjectControl;
        private final Object paginationReply;

        Builder(FoldListResp prototype) {
            replies = new ArrayList<>(prototype.replies);
            subjectControl = prototype.subjectControl;
            paginationReply = prototype.paginationReply;
        }

        public Builder clearReplies() { replies.clear(); return this; }
        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }
        public Builder setSubjectControl(SubjectControl value) { subjectControl = value; return this; }
        public FoldListResp build() { return new FoldListResp(replies, subjectControl, paginationReply); }
    }
}
