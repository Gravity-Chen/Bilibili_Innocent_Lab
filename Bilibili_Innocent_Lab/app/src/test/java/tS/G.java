package tS;

/** JVM ABI fixture only; this is not Native DexKit or Android rendering evidence. */
public abstract class G {
    public final H a;
    private final UiDescriptor descriptor;
    protected G(H item) { this(item, com.bilibili.bplus.followinglist.module.item.ModuleEnum.Author.viewType()); }
    protected G(H item, int viewType) { a = item; descriptor = new UiDescriptor(viewType); item.n.add(this); }
    public final H M() { return a.j == null ? a : a.j; }
    public boolean O() { return true; }
    public final UiDescriptor Q() { return descriptor; }
}
