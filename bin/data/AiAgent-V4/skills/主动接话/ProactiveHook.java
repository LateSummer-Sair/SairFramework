import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 主动接话（{@code on_message} 钩子 + {@code context} 注入）：
 * 判断"这条没被 @ 的群消息要不要接"，该接时只往上下文注入一段"为什么该接"的说明。
 *
 * <p><b>从不直接发消息</b>：发不发、怎么发仍然由基板判定与模型自己决定；
 * 钩子明确把 {@code payload._handled} 置 false（交回基板），并记录 kv 供观测。</p>
 *
 * <p>默认保守：没配关键词、或 {@code 启用} 不为 true，就完全不接手。条件与文案全读自技能 {@code prompt.md}。</p>
 */
public class ProactiveHook implements Hooked {

    private static final String NAME = "主动接话";
    private static final String KV_LAST_TS = "talk_last_ts";
    private static final String KV_DAY = "talk_day";
    private static final String KV_DAY_COUNT = "talk_day_count";
    private static final String KV_LAST = "talk_last";

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_message".equals(hook) || payload == null || h == null) return;
        try {
            Cfg c = load(h);
            if (!c.enabled || c.words.isEmpty()) return;

            Ev e = Ev.parse(payload);
            if (!e.isGroup() || e.groupId() <= 0L || e.userId() <= 0L) return;
            if (e.userId() == e.selfId()) return;                       // 自己说的不算
            if (e.atSelf()) return;                                     // 被 @ 由基板接手，不抢
            Caller caller = h.caller();
            if (caller != null && caller.master()) return;              // 主人的消息基板一定回
            if (!c.groups.isEmpty() && !c.groups.contains(Long.valueOf(e.groupId()))) return;
            if (J.b(payload, "_handled", false)) return;                // 已被别的技能接管
            String text = e.plainText();
            if (Str.blank(text)) return;

            String word = hit(c.words, text);
            if (word == null) return;                                   // 没命中关键词 → 不接

            long now = System.currentTimeMillis();
            // 权限：本技能只有一个 on_message 钩子、不注册任何工具，没有工具名也就没有自己的 op 可判 ——
            // 钩子跑在 SYSTEM 主体上（她自己的自主行为），技能管控表对 SYSTEM 恒全权（放行）。
            // 下面这些全是业务判断（冷却 / 日配额），照旧。
            long last = num(h.store().kv(KV_LAST_TS, "0"), 0L);
            if (c.gapSec > 0 && now - last < c.gapSec * 1000L) {
                log(h, "命中「" + word + "」但还在冷却（" + (c.gapSec - (now - last) / 1000L) + " 秒后再考虑）");
                return;
            }
            String day = Str.day();
            long used = day.equals(Str.trim(h.store().kv(KV_DAY, ""))) ? num(h.store().kv(KV_DAY_COUNT, "0"), 0L) : 0L;
            if (c.dayMax > 0 && used >= c.dayMax) {
                log(h, "命中「" + word + "」但今天已经接过 " + used + " 次（上限 " + c.dayMax + "）");
                return;
            }

            // ---- 决定接：只注入说明 + 记 kv，绝不直接发消息 ----
            String why = Str.blank(c.why) ? "" : render(c.why, e, word);
            if (!Str.blank(why)) h.inject("context", NAME, why);
            long seq = used + 1L;
            h.store().kvSet(KV_LAST_TS, String.valueOf(now));
            h.store().kvSet(KV_DAY, day);
            h.store().kvSet(KV_DAY_COUNT, String.valueOf(seq));
            JsonObject rec = new JsonObject();
            rec.addProperty("ts", now);
            rec.addProperty("group", e.groupId());
            rec.addProperty("qq", e.userId());
            rec.addProperty("name", e.senderName());
            rec.addProperty("word", word);
            rec.addProperty("text", Str.cut(Str.oneLine(text), 120));
            rec.addProperty("why", Str.cut(why, 300));
            h.store().kvSet(KV_LAST, J.json(rec));

            payload.addProperty("_handled", false);                      // 交回基板判定，不代答
            // 但"要不要自动回"这一票得投 true：基板的地址规则是"群里只认被 @"，
            // 不投票的话这条（没被 @ 的群消息）会被直接跳过，本技能等于白开。
            // 注意：本技能仍然**从不自己发消息**，只是说一句"这条值得回"。
            payload.addProperty("_reply", true);
            payload.addProperty("_reply_reason", "proactive_" + word);
            log(h, "决定接话：群 " + e.groupId() + " " + e.senderName() + "(" + e.userId()
                    + ") 命中「" + word + "」，今日第 " + seq + " 次（只注入 context，不发消息）");
        } catch (Throwable t) {
            log(h, "处理异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 配置

    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        try {
            File dir = new File(h.conf().skillsDir(), NAME);
            String text = Fs.read(new File(dir, "prompt.md"), "");
            if (Str.blank(text)) return c;
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf(':');
                if (i < 0) i = line.indexOf('：');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if ("启用".equals(k)) c.enabled = truthy(v);
                else if ("关键词".equals(k)) c.words = split(v);
                else if ("间隔秒".equals(k)) c.gapSec = (int) Math.max(0L, num(v, 300L));
                else if ("每日上限".equals(k)) c.dayMax = (int) Math.max(0L, num(v, 5L));
                else if ("只在这些群".equals(k)) c.groups = longs(v);
                else if ("接话说明".equals(k)) c.why = v;
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static final class Cfg {
        boolean enabled = false;
        int gapSec = 300;
        int dayMax = 5;
        List<String> words = new ArrayList<String>();
        Set<Long> groups = new HashSet<Long>();
        String why = "";
    }

    private static String hit(List<String> words, String text) {
        String low = text.toLowerCase();
        for (String w : words) {
            if (Str.blank(w)) continue;
            if (low.indexOf(w.toLowerCase()) >= 0) return w;
        }
        return null;
    }

    private static String render(String tpl, Ev e, String word) {
        String s = tpl;
        s = s.replace("{name}", Str.nz(e.senderName()));
        s = s.replace("{qq}", String.valueOf(e.userId()));
        s = s.replace("{group}", String.valueOf(e.groupId()));
        s = s.replace("{text}", Str.cut(Str.oneLine(e.plainText()), 120));
        s = s.replace("{word}", Str.nz(word));
        return s;
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<Long> longs(String v) {
        Set<Long> out = new HashSet<Long>();
        for (String s : split(v)) {
            try {
                out.add(Long.valueOf(Long.parseLong(s)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
