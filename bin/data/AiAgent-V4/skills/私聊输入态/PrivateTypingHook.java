import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import sair.v4.ext.BuildContext;
import sair.v4.ext.LifecycleHook;
import sair.v4.skill.Host;

/**
 * Private-chat typing indicator: turn start turns it on, turn end turns it off.
 *
 * <h3>Where it hangs</h3>
 * <p>{@link LifecycleHook}, {@link #order()} = {@code 50}. The substrate dispatches
 * {@code onTurnStart} after assembly and {@code onTurnEnd} once the reply was sent <i>or</i> the
 * turn failed -- for a main turn <b>and</b> for the internal background wrap-up turn
 * ({@code Agent.wake}; that one never fires {@code onSend}). Sub-agent turns do <b>not</b> fire
 * the lifecycle hooks at all. Opening on start and closing on end is therefore the whole plugin.</p>
 *
 * <h3>How it acts</h3>
 * <p>Only through {@code b.host().typing(boolean)} -- the substrate's single named capability
 * (see {@code Host.typing}). That capability wraps NapCat's {@code set_input_status}, and it sends
 * through the <b>gated</b> Api instance with an explicit {@code SYSTEM} binding (so ordinary users
 * see the indicator too, not just the master); it is already a no-op in a group and without a QQ
 * number. This plugin deliberately never reaches for {@code host().napcat()} and never names an
 * action itself: that path is permission-gated, and the substrate has already collapsed this use
 * case into one named action on purpose.</p>
 *
 * <h3>Group chats and the local console: nothing at all</h3>
 * <p>{@code b.group()} / {@code b.console()} short-circuit <b>before</b> the config read and before
 * any host call. NapCat has no group form of this action, and a console turn is not a private chat
 * window with anything to show -- so "do not call" is the correct behaviour rather than "call and
 * let the substrate swallow it".</p>
 *
 * <h3>Nothing escapes</h3>
 * <ul>
 *   <li>{@code b == null} -&gt; return;</li>
 *   <li>{@code b.group()} -&gt; return (group chat);</li>
 *   <li>{@code b.console()} -&gt; return (local console: not a private chat window);</li>
 *   <li>{@code b.qq() &lt;= 0} -&gt; return (a bookkeeping turn with no QQ target: nothing to show);</li>
 *   <li>{@code b.host() == null} -&gt; return (the host is not bound to this plugin, e.g. a probe
 *       that registered a synthetic implementation: do less, never throw);</li>
 *   <li>the on/off key in {@code prompt.md} (see {@link #K_ON}) is false -&gt; return from
 *       <b>both</b> hooks, so a disabled plugin makes zero calls;</li>
 *   <li>every {@code Throwable} thrown inside a hook is caught here. The substrate has its own
 *       guard around each extension call, but this plugin does not rely on it.</li>
 * </ul>
 *
 * <p><b>Stateless by design.</b> {@code schedule.Lanes} keeps one session's turns ordered (same
 * session queued, different sessions parallel), so within a session a turn's start and end always
 * pair up one-to-one. That makes a per-session open counter unnecessary -- and a counter could go
 * stale after a hot reload or a missed end, which is exactly the state that would leave the
 * indicator stuck on.</p>
 */
public class PrivateTypingHook implements LifecycleHook {

    /**
     * The on/off key in {@code prompt.md} (two CJK characters), written as a unicode escape so this
     * source file stays pure ASCII. javac decodes the escape before parsing, and the substrate
     * compiles plugin sources with {@code -encoding UTF-8} anyway, so the key matches the file.
     */
    private static final String K_ON = "\u5f00\u5173";      // kai guan

    // ==================== LifecycleHook ====================

    @Override
    public int order() {
        return 50;
    }

    /** A main turn is starting: show "typing" in a private chat. */
    @Override
    public void onTurnStart(BuildContext b) {
        toggle(b, true);
    }

    /**
     * A main turn is over: hide it again.
     *
     * <p>{@code reply} and {@code error} are deliberately ignored -- the indicator is closed on the
     * success path and on the failure path alike. A half-open state here would leave the bot
     * "typing" forever, which is the one thing this plugin must never do.</p>
     */
    @Override
    public void onTurnEnd(BuildContext b, String reply, String error) {
        toggle(b, false);
    }

    // ==================== action ====================

    /**
     * The single decision point shared by both hooks: is there anything to turn on or off here,
     * and who do we ask? Never throws.
     */
    private static void toggle(BuildContext b, boolean on) {
        try {
            if (b == null) return;
            if (b.group() || b.console()) return;        // group chat: no such action; console: not a private chat window
            if (b.qq() <= 0) return;                     // no QQ target: nothing could be shown
            Host h = b.host();
            if (h == null) return;                       // host not bound: do less, never throw
            if (!Cfg.enabled(h)) return;                 // switch off: both hooks return early
            h.typing(on);                                // the substrate's named capability
        } catch (Throwable ignored) {
            // Nothing escapes a plugin hook. The substrate guards extension calls too, but a plugin
            // must not depend on that guard.
        }
    }

    // ==================== prompt.md (lazy cache, invalidated by mtime) ====================

    /**
     * The single enable-switch value ({@link #K_ON}) from this plugin's own {@code prompt.md}.
     *
     * <p>Read as key/value lines in UTF-8, with {@code #} starting a comment and the first
     * half-width or full-width colon separating key from value. Cached by "file + mtime", so no turn
     * touches the disk and an edit takes effect on the next turn without a restart.</p>
     *
     * <p>Fallback is <b>true</b> -- byte-for-byte the shipped default in {@code prompt.md} -- which
     * covers both "file missing" and "{@code host()} is null / has no directory".</p>
     */
    private static final class Cfg {

        private static File file;
        private static long stamp = -1L;
        private static boolean on = true;

        static synchronized boolean enabled(Host h) {
            if (h == null) return true;
            try {
                File dir = h.skillDir();
                if (dir == null) return true;
                File f = new File(dir, "prompt.md");
                boolean exists = f.isFile();
                long m = exists ? f.lastModified() : 0L;
                if (file != null && file.equals(f) && m == stamp) return on;
                boolean next = exists ? read(f) : true;
                file = f;
                stamp = m;
                on = next;
                return next;
            } catch (Throwable t) {
                return true;                                 // unreadable = fall back, never throw
            }
        }

        /** Returns the switch value, or the fallback when the file has no usable key. */
        private static boolean read(File f) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                    String s = line.trim();
                    if (s.isEmpty() || s.charAt(0) == '#') continue;
                    int a = s.indexOf(':');
                    int z = s.indexOf('\uff1a');             // full-width colon
                    int k = (a < 0) ? z : (z < 0 ? a : Math.min(a, z));
                    if (k <= 0) continue;
                    if (!K_ON.equals(s.substring(0, k).trim())) continue;
                    return truth(s.substring(k + 1).trim(), true);
                }
            } catch (Throwable ignored) {
                // unreadable = fall back
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return true;
        }

        /**
         * Truthy values, same spelling set as the other plugins use (the CJK words are unicode
         * escapes: shi / fou / kai / guan). Anything unrecognised keeps the default.
         */
        private static boolean truth(String v, boolean def) {
            if (v == null) return def;
            String s = v.trim().toLowerCase();
            if (s.isEmpty()) return def;
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                    || s.equals("\u662f") || s.equals("\u5f00")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                    || s.equals("\u5426") || s.equals("\u5173")) return false;
            return def;
        }

        private Cfg() { }
    }
}
