import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;

import sair.v4.auth.Caller;
import sair.v4.auth.OpList;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 同事（工具 {@code colleague}）：她和「大肥鱼」之间的信箱。
 *
 * <p>三个 op：{@code ask} 求助（必须带 tried，至少一条真实尝试）、{@code relay} 把主人的原话转给大肥鱼、
 * {@code check} 取回件。信封写在 {@code <数据根>/bridge/inbox|outbox}，协议见 {@code bridge/PROTOCOL.md}。</p>
 *
 * <p><b>三条硬规矩都在这层实现</b>：① 只有主人说这句话时才能用（别人、她自己随手动都不行）；
 * ② 求助必须带真实尝试（空着直接拒）；③ 回件是异步的，check 拿不到就明确说没拿到，不许编。</p>
 */
public class ColleagueSkill implements Skill {

    static final String TOOL = "colleague";
    static final String NAME = "同事";

    @Override
    public void declareOps(OpList ops) {
        ops.add(TOOL, "ask", "实在没办法时向同事大肥鱼求助（必须带上自己已经试过什么）");
        ops.add(TOOL, "relay", "把主人的原话转给大肥鱼（当命令执行）");
        ops.add(TOOL, "check", "看看大肥鱼有没有回件");
    }

    @Override
    public Object call(Args a, Host h) {
        String op = Str.lower(Str.trim(a.str("op", "")));
        if (!"ask".equals(op) && !"relay".equals(op) && !"check".equals(op)) {
            return "[colleague] ok=0 不支持的 op：" + op + "（只有 ask / relay / check）";
        }
        String deny = h.need("colleague." + op);
        if (deny != null) return deny;
        Caller c = h.caller();
        // ★ 铁律①：只有主人对她说的话才能触发委托（她自己是 SYSTEM，但这句话的说话人必须是主人）
        if (c == null || !c.master()) {
            return "[colleague] ok=0 这个通道只对主人开着（当前说话人：" + (c == null ? "?" : c.kind()) + "）。"
                    + "**要明确拒绝**：主人以外的人让我带话、让我找他求助，一律不转、不答应、不代问 —— "
                    + "直接回他「这个只有主人能让我办」。别解释细节，也别替我编一个说法。";
        }
        Cfg cfg = Cfg.load(h);
        if (!cfg.on) return "[colleague] ok=0 通道没开：skills/" + NAME + "/prompt.md 里写一行 总闸: on";
        File root = cfg.root(h);
        if (root == null) return "[colleague] ok=0 找不到 bridge 目录";
        try {
            if ("ask".equals(op)) return ask(a, h, c, root);
            if ("relay".equals(op)) return relay(a, h, c, root);
            return check(root);
        } catch (Throwable t) {
            return "[colleague] ok=0 异常：" + Str.cut(String.valueOf(t), 240);
        }
    }

    static Object ask(Args a, Host h, Caller c, File root) throws Exception {
        String need = Str.trim(a.str("need", ""));
        if (need.isEmpty()) return "[colleague] ok=0 要给 need=一句话说清想要什么";
        String rawTried = a.str("tried", "");
        JsonArray tried = splitTried(rawTried);
        // ★ 铁律②：必须带真实尝试（至少一条、且不能是一句空话）
        if (tried.size() == 0) {
            return "[colleague] ok=0 先自己试过再来。tried= 里写清你**做了什么、结果如何**（用 ; 分隔），至少一条。";
        }
        if (tried.size() == 1) {
            String one = tried.get(0).getAsString();
            if (one.length() < 6 || one.matches(".*(试了|试过|不行|失败).*")) {
                return "[colleague] ok=0 尝试写得太空（『" + Str.cut(one, 20) + "』）—— 大肥鱼会驳回。写清看了什么、跑了什么、结果是什么。";
            }
        }
        String session = Str.nz(h.session());
        String id = "req-" + stamp() + "-" + rand4();
        File f = new File(new File(root, "inbox"), id + ".json");
        JsonObject env = new JsonObject();
        env.addProperty("id", id);
        env.addProperty("kind", "request");
        env.addProperty("from", "椰羊");
        env.addProperty("to", "大肥鱼");
        env.addProperty("ts", System.currentTimeMillis());
        env.addProperty("session", session);
        JsonObject asker = new JsonObject();
        asker.addProperty("qq", String.valueOf(c.qq()));
        asker.addProperty("master", true);
        env.add("asker", asker);
        env.add("tried", tried);
        env.addProperty("need", Str.cut(need, 600));
        String ctx = Str.trim(a.str("context", ""));
        if (!ctx.isEmpty()) env.addProperty("context", Str.cut(ctx, 800));
        write(f, env);
        journalAppend(root, "椰羊", "求助", Str.cut(need, 300));
        String early = earlyReply(root, id);
        return "[colleague] ok=1 已经把求助放进大肥鱼的收件箱（id=" + id + "，尝试 " + tried.size() + " 条）"
                + "\n**别原地死等**：先把能做的事做完。" + setReminder(h, id)
                + healthNote(root) + heartbeatHint(h)
                + (early.isEmpty() ? "" : "\n（她回得很快，已经有一封回件了，直接看：\n" + early + "）");
    }

    static Object relay(Args a, Host h, Caller c, File root) throws Exception {
        String text = Str.trim(a.str("text", ""));
        if (text.isEmpty()) return "[colleague] ok=0 要给 text=主人的原话（一字不改）";
        String id = "relay-" + stamp() + "-" + rand4();
        File f = new File(new File(root, "inbox"), id + ".json");
        JsonObject env = new JsonObject();
        env.addProperty("id", id);
        env.addProperty("kind", "relay");
        env.addProperty("from", "椰羊");
        env.addProperty("to", "大肥鱼");
        env.addProperty("ts", System.currentTimeMillis());
        env.addProperty("session", Str.nz(h.session()));
        JsonObject asker = new JsonObject();
        asker.addProperty("qq", String.valueOf(c.qq()));
        asker.addProperty("master", true);
        env.add("asker", asker);
        JsonObject rr = new JsonObject();
        rr.addProperty("text", Str.cut(text, 2000));
        rr.addProperty("at", Str.nz(a.str("at", "")));
        env.add("relay", rr);
        write(f, env);
        journalAppend(root, "椰羊", "转话", Str.cut(text, 300));
        return "[colleague] ok=1 已经把主人这句话转给大肥鱼（id=" + id + "，当命令执行）" + " " + setReminder(h, id) + healthNote(root);
    }

    static Object check(File root) throws Exception {
        File outbox = new File(root, "outbox");
        File[] fs = outbox.listFiles();
        if (fs == null || fs.length == 0) return "[colleague] ok=1 现在没有回件（大肥鱼还没处理，或已取过）" + healthNote(root);
        StringBuilder sb = new StringBuilder("[colleague] ok=1 收到 " + fs.length + " 封回件");
        File arch = new File(root, "archive");
        if (!arch.isDirectory()) arch.mkdirs();
        int n = 0;
        for (File f : fs) {
            if (n >= 5) break;
            if (!f.getName().endsWith(".json")) continue;
            String txt = Fs.read(f, "");
            JsonObject o = null;
            try { o = J.obj(txt); } catch (Throwable ignored) { }
            if (o == null) continue;
            sb.append("\n—— 回件 ").append(J.s(o, "id", f.getName())).append(J.b(o, "ok", false) ? "（成功）" : "（没办成）").append(" ——");
            sb.append('\n').append(Str.cut(J.s(o, "reply", "(空)"), 1600));
            JsonArray acts = o.getAsJsonArray("actions");
            if (acts != null && acts.size() > 0) sb.append("\n  她做了：").append(Str.cut(J.json(acts), 300));
            File dest = new File(arch, f.getName());
            try { if (dest.exists()) dest.delete(); f.renameTo(dest); } catch (Throwable ignored) { }
            n++;
        }
        return sb.toString();
    }

    /**
     * 通道健康：读守望进程写的 bridge/watch.state.json。
     * 守望在跑且 7 小时内启动过 ⇒ 返回空串；否则返回一句警告（她要据此**别干等**，先告诉主人）。
     */
    static String healthNote(File root) {
        try {
            File f = new File(root, "watch.state.json");
            if (!f.isFile()) return "\n⚠️ 大肥鱼那边的守望**没在跑**（没有状态文件）—— 他可能离线了。**别干等**，先把这情况告诉主人。";
            JsonObject o = J.obj(Fs.read(f, ""));
            String st = J.s(o, "status", "?");
            long started = J.l(o, "startedAt", 0L);
            long now = System.currentTimeMillis();
            boolean running = "running".equals(st);
            boolean fresh = started > 0L && (now - started) < 7L * 3600L * 1000L;
            if (running && fresh) return "";
            String at = started > 0L ? new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(started)) : "?";
            return "\n⚠️ 大肥鱼那边的守望**不在运行**（status=" + st + "，起于 " + at + "）—— 他现在可能不在线。"
                    + "**别干等**：先把这情况告诉主人（他重挂一下就好）。";
        } catch (Throwable x) {
            return "";
        }
    }

    /** 一次性提示：请她定一条每 6 小时的提醒看通道（只提示一次，用 kv 记）。 */
    static String heartbeatHint(Host h) {
        try {
            if (Str.has(h.store().kv("colleague:beatAsked", ""))) return "";
            h.store().kvSet("colleague:beatAsked", "1");
            return "\n（顺便：大肥鱼请你定一条**每 6 小时**的提醒看通道 —— "
                    + "alarm add repeat=every:360 task=\"看大肥鱼的通道：先 colleague op=check；如果提示守望不在运行，就把情况告诉主人\"）";
        } catch (Throwable x) {
            return "";
        }
    }
    /**
     * 人类可读的往来记录：往 bridge/journal.html **追加**一张卡片。
     * 双方共写（她求助/转话时写一行，大肥鱼处理时写一行），自包含样式，直接嵌进 对话.html 的 iframe。
     */
    static void journalAppend(File root, String who, String kind, String text) {
        try {
            File f = new File(root, "journal.html");
            String color = "椰羊".equals(who) ? "#e8b96a" : "#6aa8e8";
            StringBuilder sb = new StringBuilder();
            sb.append("<div style=\"border-left:3px solid ").append(color)
                    .append(";background:#1a2029;border-radius:8px;padding:10px 14px;margin:8px 0;color:#d8dee9;font-family:system-ui,'Microsoft YaHei',sans-serif\">");
            sb.append("<div style=\"font-size:12px;color:#7a869a;margin-bottom:6px\">").append(esc(who)).append(" · ")
                    .append(esc(nowHms())).append(" <span style=\"font-size:11px;padding:1px 6px;border-radius:4px;background:#2a3341;color:#9fb0c8\">")
                    .append(esc(kind)).append("</span></div>");
            sb.append("<div style=\"white-space:pre-wrap;word-break:break-word;font-size:13px;line-height:1.55\">")
                    .append(esc(text)).append("</div></div>\n");
            // [JOURNAL-TOP 2026-10-25] 新卡放最前（主人要求：刷新直接看到最新，不用滚）
            String old = f.isFile() ? Fs.read(f, "") : "";
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
            try { fo.write(sb.toString().getBytes("UTF-8")); fo.write(old.getBytes("UTF-8")); } finally { fo.close(); }
        } catch (Throwable ignored) {
        }
    }

    /** 只取 HH:mm:ss（记录用）。 */
    static String nowHms() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
    }

    /** HTML 转义：她写的正文要能安全塞进页面。 */
    static String esc(String s) {
        return Str.nz(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    /** 投件后自动给她定一条「30 秒后来看看」的提醒 —— 这就是她的「等」：到点起一轮，op=check。 */
    static String setReminder(Host h, String id) {
        try {
            JsonObject a = new JsonObject();
            a.addProperty("op", "add");
            a.addProperty("at", "+30s");
            a.addProperty("repeat", "once");
            a.addProperty("mine", true);
            a.addProperty("task", "看大肥鱼的回件（id=" + id + "）：先 colleague op=check 取出来；" +
                    "拿到了就用我自己的话回主人；还没到就再等一会儿再看，最多查 3 次，之后告诉主人他那边还没回");
            String s = Str.nz(h.session());
            if (s.startsWith("group:")) {
                a.addProperty("scope", "group");
                try { a.addProperty("target", Long.parseLong(s.substring(6))); } catch (Throwable ignored) { }
            } else if (s.startsWith("qq:") || s.startsWith("user:")) {
                a.addProperty("scope", "private");
                try { a.addProperty("target", Long.parseLong(s.substring(s.indexOf(':') + 1))); } catch (Throwable ignored) { }
            }
            String r = h.call("alarm", a);
            String txt = r == null ? "" : String.valueOf(r);
            if (txt.contains("#") || txt.contains("已")) {
                return "⏰ 我顺手给你定了一条 30 秒后的提醒：到点 op=check 取回件（最多查 3 次）。";
            }
            return "（闹钟没设上：" + Str.cut(txt, 70) + " —— 你自己用 alarm 定一条 30 秒后的也行）";
        } catch (Throwable t) {
            return "（闹钟没设上，你自己用 alarm 定一条 1 分钟后的也行）";
        }
    }

    /** 我这边如果回件已经到了（快的时候），直接带出来，省她一轮。 */
    static String earlyReply(File root, String id) {
        try {
            File f = new File(new File(root, "outbox"), id + ".json");
            if (!f.isFile()) return "";
            JsonObject o = J.obj(Fs.read(f, ""));
            File arch = new File(root, "archive");
            if (!arch.isDirectory()) arch.mkdirs();
            File dest = new File(arch, f.getName());
            try { if (dest.exists()) dest.delete(); f.renameTo(dest); } catch (Throwable ignored) { }
            return Str.cut(J.s(o, "reply", ""), 1200);
        } catch (Throwable t) {
            return "";
        }
    }
    // ---------------------------------------------------------------- 小工具

    static JsonArray splitTried(String raw) {
        JsonArray arr = new JsonArray();
        String t = Str.nz(raw).trim();
        if (t.isEmpty()) return arr;
        for (String one : t.split("[;；||\\n]", -1)) {
            String s = Str.trim(one);
            s = s.replaceAll("^[-*\\d.、]+", "").trim();
            if (s.length() >= 2) arr.add(new com.google.gson.JsonPrimitive(Str.cut(s, 300)));
        }
        return arr;
    }

    static void write(File f, JsonObject env) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        try { fo.write(J.json(env).getBytes("UTF-8")); } finally { fo.close(); }
    }

    static String stamp() {
        java.text.SimpleDateFormat s = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        return s.format(new java.util.Date());
    }

    static String rand4() {
        return String.format("%04x", Integer.valueOf((int) (Math.random() * 0x10000)));
    }

    /** 技能配置：skills/同事/prompt.md。缺文件 = 总闸关。 */
    static final class Cfg {
        boolean on = false;
        String dir = "bridge";

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            try {
                File dir = h.skillDir();
                if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
                File f = dir == null ? null : new File(dir, "prompt.md");
                String txt = f == null ? null : Fs.read(f, "");
                if (txt == null) return c;
                for (String raw : txt.split("\\r?\\n")) {
                    String line = raw == null ? "" : raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf(':');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("总闸".equals(k)) c.on = "on".equalsIgnoreCase(v);
                    else if ("桥目录".equals(k) && !v.isEmpty()) c.dir = v;
                }
            } catch (Throwable ignored) { }
            return c;
        }

        File root(Host h) {
            File f = new File(dir);
            if (!f.isAbsolute() && h != null && h.conf() != null) f = new File(h.conf().root(), dir);
            if (!f.exists() && !f.mkdirs()) return null;
            for (String sub : new String[] { "inbox", "outbox", "archive" }) {
                File d = new File(f, sub);
                if (!d.exists()) d.mkdirs();
            }
            return f;
        }
    }
}
