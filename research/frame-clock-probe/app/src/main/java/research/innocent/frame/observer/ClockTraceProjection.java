package research.innocent.frame.observer;

/** Bounded primitive observations, never a media clock or an ownership permit. */
final class ClockTraceProjection {
    private ClockTraceProjection() {}
    static String statistics(long[] state) {
        if (state == null || state.length != 10 || (state[0] & ~255L) != 0 ||
                (state[1] & ~15L) != 0 || (state[2] & ~state[1]) != 0 ||
                state[3] < 0 || state[4] < 0 || state[4] > 1 || state[5] < 0)
            throw new IllegalArgumentException();
        StringBuilder out = new StringBuilder("{\"flags\":").append(state[0])
                .append(",\"attempted_mask\":").append(state[1]).append(",\"installed_mask\":").append(state[2])
                .append(",\"drops\":").append(state[3]).append(",\"collection_epoch\":").append(state[4])
                .append(",\"call_ids_issued\":").append(state[5]).append(",\"hook_returns\":[");
        for (int i = 0; i < 4; i++) {
            if (i != 0) out.append(',');
            if ((state[1] & (1L << i)) == 0) out.append("null");
            else {
                if (state[6 + i] < Integer.MIN_VALUE || state[6 + i] > Integer.MAX_VALUE) throw new IllegalArgumentException();
                out.append(state[6 + i]);
            }
        }
        return out.append("]}").toString();
    }
    static void adoptStatistics(NativeEntryResult result, long[] state) {
        statistics(state);
        result.callbackRegistered = false;
        result.hookAttempts = Integer.bitCount((int)state[1]);
        result.hostHooksInstalled = Integer.bitCount((int)state[2]);
    }
    static String events(long[] values) {
        if (values == null || values.length % 16 != 0 || values.length > 64 * 16) throw new IllegalArgumentException();
        StringBuilder out = new StringBuilder("{\"schema\":7,\"type\":\"observations\",\"events\":[");
        for (int at = 0; at < values.length; at += 16) {
            if (values[at] != 1 && values[at] != 2 || values[at + 1] <= 0 || values[at + 3] < 1 || values[at + 3] > 32 ||
                    values[at + 4] < 0 || values[at + 4] > 128 || values[at + 15] < 0 ||
                    values[at] == 1 && (values[at + 10] < 0 || values[at + 10] > 128)) throw new IllegalArgumentException();
            if (at != 0) out.append(',');
            out.append('[');
            for (int i = 0; i < 16; i++) {
                if (i != 0) out.append(',');
                if (i == 7 || i == 11) out.append('"').append(Long.toUnsignedString(values[at + i], 16)).append('"');
                else out.append(values[at + i]);
            }
            out.append(']');
        }
        out.append("]}");
        if (out.length() > 32767) throw new IllegalArgumentException();
        return out.toString();
    }
}
