package research.innocent.frame.observer;
public final class ClockProjectionSelfTest {
 public static void main(String[] args) {
  if (!NativeEntryModule.allowedProcess("tv.danmaku.bili:ijkservice") || NativeEntryModule.allowedProcess("tv.danmaku.bili:download")) throw new AssertionError();
  if (!ClockTraceProjection.statistics(new long[10]).contains("[null,null,null,null]")) throw new AssertionError();
  boolean rejected=false;try { ClockTraceProjection.statistics(new long[]{0,0,1,0,0,0,0,0,0,0}); } catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError();
  long[] event=new long[16];event[0]=1;event[1]=1;event[3]=1;event[7]=0x7ff8000000001234L;
  if(!ClockTraceProjection.events(event).contains("7ff8000000001234"))throw new AssertionError();
  event[0]=3;rejected=false;try{ClockTraceProjection.events(event);}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError();
  System.out.println("CLOCK_PROJECTION PASS checks=5 device=false native=false");
 }
}
