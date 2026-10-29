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
 * 语音技能（工具 {@code speak}）：把文字合成语音。
 *
 * <p>底层是 <b>edge-tts</b>（微软 Edge 的在线朗读接口）：免费、不下载模型、不吃显存，
 * 一句三秒左右出 mp3。技能自己不实现协议，只负责"写文本文件 → 调 python → 收发文件"。</p>
 *
 * <h3>为什么男女声要自动判</h3>
 * <p>她是一个有固定人设的角色，说话声线应当<b>跟人设一致</b>，不该每次都让主人交代性别。
 * 所以按"参数 → 配置 → <b>她自己提示词里的性别关键词</b> → 默认女声"四层决定。
 * 提示词没提性别时<b>默认女声</b>（这是主人定的口径）。</p>
 *
 * <h3>为什么文本要落文件再传</h3>
 * <p>文本来自模型/用户，直接拼进命令行就是命令注入洞。所以把它写进 UTF-8 文本文件，
 * 命令行里只出现<b>我们自己生成的路径</b>和音色 ID（{@code edge_tts -f <file>}）。</p>
 */
public class SpeakSkill implements Skill {

    static final String TOOL = "speak";
    /** [VOICEONCE] 单条语音上限：超过就不发起（见发送前护栏的说明）。 */
    static final long MAX_VOICE_BYTES = 16L * 1024L * 1024L;
    static final String NAME = "语音";

    /** 单次最多合成多少字。超了拒，让她分段说。 */
    static final int MAX_CHARS = 300;

    static final String DEF_FEMALE = "zh-CN-XiaoxiaoNeural";
    static final String DEF_MALE = "zh-CN-YunxiNeural";

    private static final Object LOCK = new Object();
    private static long windowStart = 0L;
    private static int windowCount = 0;

    @Override
    public void declareOps(OpList ops) {
        ops.add(TOOL, "", "把文字合成语音并发出（edge-tts）。**这是拓展能力，只在对方要求发语音时才用，平时聊天一律文本**");
        ops.add(TOOL, "voices", "列出可用的中文音色");
    }

    @Override
    public Object call(Args a, Host h) {
        String deny = h == null ? null : h.need("speak");
        if (deny != null) return deny;
        String op = Str.lower(Str.trim(a.str("op", "speak")));
        if (op.isEmpty()) op = "speak";
        if ("voices".equals(op)) return voices();
        if (!"speak".equals(op)) return "[speak] ok=0 不支持的 op：" + op + "（只有 speak / voices）";

        String text = Str.trim(a.str("text", ""));
        if (text.isEmpty()) return a.missing("text");
        int len = text.codePointCount(0, text.length());
        if (len > MAX_CHARS) {
            return "[speak] ok=0 太长（" + len + " 字，上限 " + MAX_CHARS + "）——分段说，一次一段。";
        }
        Cfg c = Cfg.load(h);
        // 去重：同一句话 20 秒内已经发过就【不再发第二条】。
        // 真机踩过：她会因为事实行里的 gender= 而怀疑没发对，回头把同一句再发一遍。
        String dup = alreadySpoken(h, text);
        if (dup != null) return dup;
        if (!spend(c)) return "[speak] ok=0 语音预算用尽（每分钟 " + c.perMin + " 次），等一会儿。";
        if (h == null || h.conf() == null || h.conf().filesDir() == null) return "[speak] ok=0 读不到数据目录";

        File dir = new File(h.conf().filesDir(), "voice");
        if (!dir.isDirectory() && !dir.mkdirs()) return "[speak] ok=0 建不了目录：" + dir;

        String gender = resolveGender(h, a, c);
        String voice = pickVoice(h, a, c, gender);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date());
        File txt = new File(dir, "text-" + stamp + ".txt");
        File mp3 = new File(dir, "voice-" + voiceShort(voice) + "-" + stamp + ".mp3");
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(txt);
            try { fo.write(text.getBytes("UTF-8")); } finally { fo.close(); }
        } catch (Throwable t) {
            return "[speak] ok=0 写文本文件失败：" + t;
        }

        String[] tone = resolveTone(a, c);
        String rate = tone[0];
        String volume = tone[1];
        String pitch = tone[2];
        StringBuilder cmd = new StringBuilder();
        cmd.append(Str.blank(c.python) ? "python" : c.python);
        cmd.append(" -m edge_tts -f \"").append(txt.getAbsolutePath()).append("\"");
        cmd.append(" -v ").append(voice);
        if (Str.has(rate)) cmd.append(" --rate ").append(rate);
        if (Str.has(volume)) cmd.append(" --volume ").append(volume);
        if (Str.has(pitch)) cmd.append(" --pitch ").append(pitch);
        cmd.append(" --write-media \"").append(mp3.getAbsolutePath()).append("\" --no-progress");

        String execOut = "";
        try {
            JsonObject e = new JsonObject();
            e.addProperty("kind", "cmd");
            e.addProperty("cmd", cmd.toString());
            e.addProperty("timeout", 90000);
            execOut = h.call("exec", e);
        } catch (Throwable t) {
            execOut = "exec 异常：" + t;
        }
        // [VOICEONCE] 发送前的大小护栏：超过 16MB 的音频不发起（上传必超时、还会诱发疑似重复发送）
        if (mp3.isFile() && mp3.length() > MAX_VOICE_BYTES) {
            try { txt.delete(); } catch (Throwable ignored) { }
            return "[speak] ok=0 音频太大（" + (mp3.length() / 1024 / 1024) + "MB，上限 "
                    + (MAX_VOICE_BYTES / 1024 / 1024) + "MB），**没发** —— 高码率/超长音频上传会超时，"
                    + "更容易被平台限流。要发就换短一点的、或低码率的。别重试，也别换成文件发。";
        }
        if (!mp3.isFile() || mp3.length() < 512L) {
            return "[speak] ok=0 合成失败（没出音频）：" + Str.cut(Str.oneLine(execOut), 300)
                    + "\n（检查：python 在 PATH 上？装了 edge-tts？能连微软接口？）";
        }

        // 发出去：QQ 会话里发语音消息；**发不出去就如实报错 —— 不重试、不改道**（主人 2026-10-24 明确要求）。
        // [VOICEONCE 2026-10-24] 原来的口径是「record 失败 ⇒ 改用文件口再发一次」，真机上就变成
        //   高码率音频上传慢 ⇒ record 超时返回错误（其实可能已经发出去了）⇒ 文件口又发一遍 ⇒ 多条语音。
        //   现在：只调一次，成败都收手；失败把错误写进 sendError 让她如实告诉对方。
        Caller me = h.caller();
        String sent = "none";
        String sendErr = "";
        if (me != null && !me.isConsole()) {
            boolean group = me.isGroup();
            long target = group ? me.groupId() : me.qq();
            try {
                JsonObject r = h.sayMedia(group, target, "record", mp3.getAbsolutePath());
                if (r != null && sair.v4.qq.Api.ok(r)) {
                    sent = "record";
                } else {
                    sendErr = r == null ? "记录口不承接（不是当前会话？）" : ("record 失败：" + J.s(r, "error", r.toString()));
                }
            } catch (Throwable t) {
                sendErr = "发送异常：" + t;
            }
        }

        rememberSpoken(h, text, mp3);
        String head = "[speak] ok=" + ("none".equals(sent) ? "0" : "1") + " " + ("none".equals(sent) ? "没发出去" : "已发出（不要再发第二条）") + " gender=" + gender + " voice=" + voice
                + " chars=" + len + " bytes=" + mp3.length() + " file=" + mp3.getAbsolutePath()
                + " sent=" + sent;
        if (!"none".equals(sent)) {
            try { txt.delete(); } catch (Throwable ignored) { }
            return head;
        }
        return head + (Str.has(sendErr) ? ("  sendError=" + Str.cut(sendErr, 200)) : "")
                + "\n（只落在本地，没发出去；控制台会话就是这样）";
    }

    // ---------------------------------------------------------------- 去重（同一句不重复发）

    static final String KEY_LAST = "voice:last";
    static final long DUP_WINDOW_MS = 20000L;

    /** 纯函数：存的记录是不是"同一句话、且在时间窗内"。反射可测。 */
    static boolean sameRecent(String kvValue, String text, long nowMs, long windowMs) {
        if (kvValue == null || kvValue.isEmpty() || text == null || text.isEmpty()) return false;
        JsonObject o = J.obj(kvValue);
        if (o == null) return false;
        String t = J.s(o, "t", "");
        long ts = J.l(o, "ts", 0L);
        if (!text.equals(t)) return false;
        return nowMs - ts <= windowMs;
    }

    static String alreadySpoken(Host h, String text) {
        try {
            if (h == null || h.store() == null) return null;
            String v = h.store().kv(KEY_LAST + ":" + h.session(), "");
            if (!sameRecent(v, text, System.currentTimeMillis(), DUP_WINDOW_MS)) return null;
            JsonObject o = J.obj(v);
            String f = o == null ? "" : J.s(o, "f", "");
            return "[speak] ok=1 这条刚刚已经发过了（同一句 " + (DUP_WINDOW_MS / 1000) + " 秒内不重复发）"
                    + (Str.has(f) ? ("，文件 " + f) : "")
                    + "\n（不要再发第二条；如果你确实要再发一次，换个说法或等一会儿）";
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void rememberSpoken(Host h, String text, File mp3) {
        try {
            if (h == null || h.store() == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("t", text);
            o.addProperty("f", mp3 == null ? "" : mp3.getAbsolutePath());
            o.addProperty("ts", System.currentTimeMillis());
            h.store().kvSet(KEY_LAST + ":" + h.session(), J.json(o));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 语气（edge-tts 只有三个旋钮）

    /**
     * 语气预设：edge-tts <b>没有</b>自由文本风格（"温柔地说"这种它不认），只有
     * 语速 / 音量 / 音调 三个旋钮。这里把常见语气翻译成三旋钮组合，{@code prompt.md} 可设默认。
     *
     * <p>顺序：{@code 语速}{@code 音量}{@code 音调}</p>
     */
    static String[] tonePreset(String name) {
        String t = Str.lower(Str.trim(name == null ? "" : name));
        if ("温柔".equals(t) || "gentle".equals(t) || "soft".equals(t)) return new String[] { "-12%", "-25%", "+2Hz" };
        if ("欢快".equals(t) || "happy".equals(t) || "cheer".equals(t)) return new String[] { "+8%", "+0%", "+6Hz" };
        if ("严肃".equals(t) || "serious".equals(t) || "news".equals(t)) return new String[] { "-4%", "+0%", "-6Hz" };
        if ("耳语".equals(t) || "whisper".equals(t)) return new String[] { "-15%", "-45%", "+0Hz" };
        if ("平缓".equals(t) || "calm".equals(t)) return new String[] { "-18%", "-15%", "-2Hz" };
        return null;
    }

    /**
     * 解析出最终的 语速/音量/音调。
     *
     * <p>优先级（逐项独立）：显式参数 {@code rate/volume/pitch} &gt; 配置里的同名键 &gt;
     * 语气预设（参数 {@code tone} &gt; 配置「语气:」）&gt; 空（用 edge-tts 默认）。</p>
     */
    static String[] resolveTone(Args a, Cfg c) {
        String[] base = new String[] { "", "", "" };
        String tname = Str.trim(a.str("tone", ""));
        if (!Str.has(tname)) tname = c.tone;
        String[] p = tonePreset(tname);
        if (p != null) base = p;
        String r = firstHas(a.str("rate", ""), c.rate, base[0]);
        String v = firstHas(a.str("volume", ""), c.volume, base[1]);
        String h = firstHas(a.str("pitch", ""), c.pitch, base[2]);
        return new String[] { r, v, h };
    }

    static String firstHas(String a, String b, String def) {
        if (Str.has(a)) return Str.trim(a);
        if (Str.has(b)) return Str.trim(b);
        return def == null ? "" : def;
    }

    // ---------------------------------------------------------------- 音色与性别

    static String voices() {
        return "[speak] ok=1 可用中文音色：\n"
                + "女 zh-CN-XiaoxiaoNeural      温暖自然（默认女声）\n"
                + "女 zh-CN-XiaoyiNeural        活泼，适合撒娇/卡通\n"
                + "男 zh-CN-YunxiNeural         阳光生动（默认男声）\n"
                + "男 zh-CN-YunyangNeural       新闻播音，稳重\n"
                + "男 zh-CN-YunjianNeural       有激情，适合解说\n"
                + "男 zh-CN-YunxiaNeural        偏可爱\n"
                + "女 zh-CN-liaoning-XiaobeiNeural  东北话\n"
                + "女 zh-CN-shaanxi-XiaoniNeural    陕西话\n"
                + "（传 voice=音色ID 可强制指定，优先级最高）";
    }

    /** 女 / 男 归一化；认不出返回 null。 */
    static String norm(String s) {
        String v = Str.lower(Str.trim(s == null ? "" : s));
        if (v.isEmpty()) return null;
        if (v.startsWith("女") || v.startsWith("f")) return "女";
        if (v.startsWith("男") || v.startsWith("m")) return "男";
        return null;
    }

    /**
     * 四层决定性别：gender 参数 → 配置「性别:」 → <b>她自己提示词里的关键词</b> → 默认女。
     */
    static String resolveGender(Host h, Args a, Cfg c) {
        String g = norm(a.str("gender", ""));
        if (g != null) return g;
        g = norm(c.gender);
        if (g != null) return g;
        String sys = "";
        try {
            if (h != null && h.prompts() != null) sys = h.prompts().system(h.scopeKey());
        } catch (Throwable ignored) {
        }
        if (sys == null) sys = "";
        // 【真机踩过的坑，别再犯】
        //   · 「他」在这种人设文里几乎全指**主人/对方**（甘雨那份 31 次「他」、0 次「她」），
        //     把它当性别信号 ⇒ 女角色被判成男声。**代词一律不数。**
        //   · 人设里写性别常常只有一个字：「- 甘雨，女，5000 岁」。所以必须认**声明位置**的
        //     单字性别（，女，／性别: 女），而不是只认「女生/女性」这类词。
        int f = 0;
        int m = 0;
        // ① 明确的性别声明（最强，×5）：人设里「…，女，…」「性别: 女」这类写法
        String[] fDecl = { "，女", ",女", "性别:女", "性别:女", "性别: 女", "性别：女", "性别 女", "是女的", "她是女" };
        String[] mDecl = { "，男", ",男", "性别:男", "性别：男", "性别: 男", "性别 男", "是男的", "他是男" };
        for (String w : fDecl) f += count(sys, w) * 5;
        for (String w : mDecl) m += count(sys, w) * 5;
        // ② 明确名词（×3）
        String[] fw = { "女生", "女性", "女孩", "少女", "妹子", "姑娘", "女声", "妹妹", "小姐姐", "少女感", "女孩子" };
        String[] mw = { "男生", "男性", "男孩", "少年", "男声", "哥哥", "小哥", "汉子", "男孩子" };
        for (String w : fw) f += count(sys, w) * 3;
        for (String w : mw) m += count(sys, w) * 3;
        if (m > f) return "男";
        return "女";
    }

    static int count(String s, String sub) {
        if (s == null || sub == null || sub.isEmpty()) return 0;
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** 音色 ID 的短名，写进文件名便于事后核对（zh-CN-XiaoxiaoNeural → Xiaoxiao）。 */
    static String voiceShort(String id) {
        String s = id == null ? "" : id.trim();
        int i = s.lastIndexOf('-');
        String t = i >= 0 ? s.substring(i + 1) : s;
        return t.replace("Neural", "");
    }

    /** voice 参数 > 性别对应的音色（配置可覆盖）。 */
    static String pickVoice(Host h, Args a, Cfg c, String gender) {
        String v = Str.trim(a.str("voice", ""));
        if (Str.has(v)) return v;
        if ("男".equals(gender)) return Str.blank(c.male) ? DEF_MALE : c.male;
        return Str.blank(c.female) ? DEF_FEMALE : c.female;
    }

    static boolean spend(Cfg c) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60000L) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= c.perMin) return false;
            windowCount++;
            return true;
        }
    }

    /** 技能自己的配置源：{@code skills/语音/prompt.md}，一行「键: 值」。 */
    static final class Cfg {
        String female = DEF_FEMALE;
        String male = DEF_MALE;
        String gender = "";
        String tone = "温柔";
        String rate = "";
        String volume = "";
        String pitch = "";
        String python = "python";
        int perMin = 10;

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            try {
                if (h == null || h.conf() == null) return c;
                File f = h.conf().path("skills/" + NAME + "/prompt.md");
                if (f == null || !f.isFile()) return c;
                for (String raw : Fs.read(f, "").split("\\r?\\n")) {
                    String line = raw == null ? "" : raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf(':');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("女声".equals(k) && !v.isEmpty()) c.female = v;
                    else if ("男声".equals(k) && !v.isEmpty()) c.male = v;
                    else if ("性别".equals(k)) c.gender = v;
                    else if ("语气".equals(k)) c.tone = v;
                    else if ("语速".equals(k)) c.rate = v;
                    else if ("音量".equals(k)) c.volume = v;
                    else if ("音调".equals(k)) c.pitch = v;
                    else if ("python".equals(k) && !v.isEmpty()) c.python = v;
                    else if ("每分钟上限".equals(k)) {
                        try { int n = Integer.parseInt(v); if (n > 0) c.perMin = n; } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) {
            }
            return c;
        }
    }
}
