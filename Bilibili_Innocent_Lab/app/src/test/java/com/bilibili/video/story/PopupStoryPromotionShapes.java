package com.bilibili.video.story;

import com.alibaba.fastjson.annotation.JSONField;

public final class PopupStoryPromotionShapes {
    public static final class WrongJsonParent {
        @JSONField(name = "network_status_toast")
        private FreeDataToast freeDataToast;
        public FreeDataToast getFreeDataToast() { return freeDataToast; }
    }

    public static final class MissingJsonParent {
        private FreeDataToast freeDataToast;
        public FreeDataToast getFreeDataToast() { return freeDataToast; }
    }

    public static final class StaticFieldParent {
        @JSONField(name = "free_flow_toast")
        private static FreeDataToast freeDataToast;
        public FreeDataToast getFreeDataToast() { return freeDataToast; }
    }

    public static final class DuplicateFieldParent {
        @JSONField(name = "free_flow_toast")
        private FreeDataToast freeDataToast;
        private FreeDataToast unrelatedToast;
        public FreeDataToast getFreeDataToast() { return freeDataToast; }
    }

    public static final class WrongReturnParent {
        @JSONField(name = "free_flow_toast")
        private FreeDataToast freeDataToast;
        public String getFreeDataToast() { return "network status"; }
    }

    public static final class ParameterizedReaderParent {
        @JSONField(name = "free_flow_toast")
        private FreeDataToast freeDataToast;
        public FreeDataToast getFreeDataToast(boolean state) { return freeDataToast; }
    }

    public static final class StaticReaderParent {
        @JSONField(name = "free_flow_toast")
        private FreeDataToast freeDataToast;
        public static FreeDataToast getFreeDataToast() { return null; }
    }

    public abstract static class AbstractReaderParent {
        @JSONField(name = "free_flow_toast")
        private FreeDataToast freeDataToast;
        public abstract FreeDataToast getFreeDataToast();
    }

    public static final class WrongStringModelParent {
        @JSONField(name = "free_flow_toast")
        private WrongStringModel freeDataToast;
        public WrongStringModel getFreeDataToast() { return freeDataToast; }
    }

    public static final class WrongStringModel {
        public String getTitle() { return "promotion"; }
        public int getSubtitle() { return 1; }
        public String getButtonText() { return "open"; }
        public String getUri() { return "promotion://open"; }
        public long getTipsId() { return 1; }
    }

    public static final class WrongTipsModelParent {
        @JSONField(name = "free_flow_toast")
        private WrongTipsModel freeDataToast;
        public WrongTipsModel getFreeDataToast() { return freeDataToast; }
    }

    public static final class WrongTipsModel {
        public String getTitle() { return "promotion"; }
        public String getSubtitle() { return "subtitle"; }
        public String getButtonText() { return "open"; }
        public String getUri() { return "promotion://open"; }
        public int getTipsId() { return 1; }
    }

    public static final class StaticModelReaderParent {
        @JSONField(name = "free_flow_toast")
        private StaticModelReader freeDataToast;
        public StaticModelReader getFreeDataToast() { return freeDataToast; }
    }

    public static final class StaticModelReader {
        public String getTitle() { return "promotion"; }
        public String getSubtitle() { return "subtitle"; }
        public String getButtonText() { return "open"; }
        public static String getUri() { return "promotion://open"; }
        public long getTipsId() { return 1; }
    }
}
