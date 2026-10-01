import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Libs;
import sair.v4.store.Store;

/**
 * 「情绪」的入口类：<b>自己的表 + 累积 + 衰减</b>（不是工具插件）。
 *
 * <h3>挂在哪</h3>
 * <ul>
 *   <li>{@link Skill#declare(Libs)}：<b>装载期</b>声明 {@code emotion} 表（表结构由用到它的插件声明，
 *       基板只给注册表 —— 见 {@code notes/lib-declaration.md}）；</li>
 *   <li>{@link Hooked#on} 的 {@code on_message}：一条入站消息 → 命中词表 → 怒气/伤心/亲疏/情绪标签；</li>
 *   <li>{@link Hooked#on} 的 {@code on_timer}：基板唯一 tick（周期 {@code conf.tickMs}，出厂 20 秒）
 *       → 按 {@code 衰减分钟} 结算衰减（V3 是每分钟 −2.0 怒 / −1.5 悲）。</li>
 * </ul>
 * <p>注入那一段在 {@link EmotionProvider}（同一个插件目录里的另一个顶层类，基板装载时自动识别）。</p>
 *
 * <h3>两件不许破的事</h3>
 * <ol>
 *   <li><b>只写自己的表</b>：写路径只有 {@code emotion}（{@link EmotionState}）；
 *       {@code memory}/{@code dialog}/{@code grouplog}/{@code favor} 一个字节都不写。</li>
 *   <li><b>什么都不抛</b>：{@code payload}/{@code h}/{@code h.store()} 都可能是 null，
 *       每一种都自己吞掉并少做点事 —— 钩子里的异常绝不该影响一条消息的处理。</li>
 * </ol>
 *
 * <p>文案、阈值、词表、正则全在同目录的 {@code prompt.md}（{@link EmotionCfg} 惰性缓存 + mtime 失效）；
 * 这个类里只有结构性取值与兜底默认值。</p>
 */
public class EmotionSkill implements Skill, Hooked {

    /** 技能名（= 文件夹名 = md 名），也是 {@code emotion} 表的 owner。 */
    public static final String NAME = EmotionCfg.NAME;

    /** 本插件自己的表。 */
    public static final String LIB = EmotionState.LIB;

    // ================================================================ 库声明（装载期）

    /**
     * 声明 {@code emotion} 表：一行 = 一个「人在一个场合」的情绪状态。
     *
     * <p>列设计（全部按"注入与衰减真正要用的东西"给，没有一列是摆设）：
     * {@code scope}/{@code scope_id} 是<b>按人按群隔离</b>的键
     * （私聊 = {@code private/<QQ>}；群聊 = {@code group/<群号>:<QQ>}），
     * {@code angry}/{@code sad} 是那台 0~100 的状态机，{@code affinity} 是亲疏（V3 的 affection，−100~1000），
     * {@code last_emotion} 是"对方最近一条消息的情绪标签"，{@code romance}/{@code romance_id} 是
     * V3 的恋爱中/背叛者/前任，{@code day} 管"每天首次交流"的亲疏，{@code decay_ms} 是衰减结算游标，
     * {@code hits}/{@code extra} 是诊断（命中了几条规则、命中的是哪些）。</p>
     *
     * <p>不建 FTS：这张表是"状态"，不是"文本"，没有全文检索的用法（{@code LIKE} 回退也不需要）。</p>
     */
    @Override
    public void declare(Libs libs) {
        try {
            if (libs == null) return;
            libs.register(new LibSpec(LIB, "情绪状态", NAME)
                    .ts()
                    .col("scope", "TEXT", "'private'")
                    .col("scope_id", "TEXT", "''")
                    .col("group_id", "INTEGER", "0")
                    .col("qq", "INTEGER", "0")
                    .col("angry", "REAL", "0")
                    .col("sad", "REAL", "0")
                    .col("affinity", "INTEGER", "0")
                    .col("last_emotion", "TEXT", "''")
                    .col("romance", "TEXT", "'none'")
                    .col("romance_id", "INTEGER", "0")
                    .col("day", "INTEGER", "0")
                    .col("hits", "INTEGER", "0")
                    .col("decay_ms", "INTEGER", "0")
                    .col("extra", "TEXT", "''")
                    .alias("情绪", "情绪状态", "emotion_state")
                    .idx("idx_emotion_scope", "scope, scope_id")
                    .idx("idx_emotion_ts", "ts")
                    .idx("idx_emotion_qq", "qq"));
        } catch (Throwable ignored) {
            // 声明失败只让这个插件不可用（基板装载器会记一行），不许拖垮装配
        }
    }

    // ================================================================ 工具入口（空实现）

    /**
     * <b>本插件不提供任何模型工具</b>（md 里没有工具声明），所以这里什么都不做。
     * <p>实现 {@link Skill} 只是为了拿到装载期的库声明面 —— 基板对"每个顶层类"都会问一次
     * {@code declare}，纯行为插件（只挂钩子/扩展点）靠的就是这一条。</p>
     */
    @Override
    public Object call(Args a, Host h) {
        return null;
    }

    // ================================================================ 钩子

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        try {
            if ("on_message".equals(hook)) onMessage(payload, h);
            else if ("on_timer".equals(hook)) onTimer(payload, h);
            else if ("on_notice".equals(hook)) onNotice(payload, h);
        } catch (Throwable t) {
            log(h, "钩子 " + hook + " 处理异常：" + t);
        }
    }

    /**
     * 一条入站消息 → 累积情绪。
     *
     * <p>只认 {@code post_type=message}、有 QQ 号、正文非空、且不是机器人自己发的。
     * 群消息与私聊<b>分开存</b>（见 {@link EmotionState#scopeIdOf}）：A 群的怒气不会串到 B 群。</p>
     *
     * <p><b>两条并行的账</b>：既有那一台连续 0~100 的机器（{@link EmotionState#apply}）照旧；
     * 离散 5 态的档位层（{@link EmotionMood#onMessage}）在同一处结算 ——
     * 两条各写自己的存储（{@code emotion} 表 / {@code kv} 的 {@code mood:} 前缀），互不读写对方的。</p>
     */
    private static void onMessage(JsonObject payload, Host h) {
        if (payload == null || h == null) return;
        EmotionCfg c = EmotionCfg.of(h);
        if (!c.flag("开关", true)) return;
        Store s = store(h);
        if (s == null) return;
        // 本插件不提供工具（没有 op 可判）：自主钩子跑在 SYSTEM 主体上，读写自己的表恒放行。

        Ev e = Ev.parse(payload);
        if (!e.isMessage()) return;                        // message_sent（机器人自己发的）不管
        long qq = e.userId();
        if (qq <= 0L || qq == e.selfId()) return;
        long gid = e.groupId() > 0L ? e.groupId() : 0L;
        if (gid <= 0L && !e.isPrivate()) return;            // 既不是群也不是私聊：不猜
        String text = e.plainText();
        if (Str.blank(text)) return;

        long now = eventTime(e, c);
        EmotionState.apply(s, c, qq, gid, text, now);

        // ---- 档位层（离散 5 态）：同一处结算，写自己的 kv
        try {
            List<JsonObject> pokeRows = c.flag("通知钩子", true) ? null : pokeRows(s, gid, now, c);
            EmotionMood.onMessage(s, c, h, e, text, now, pokeRows);
            EmotionMood.flush(s);                           // 转移日志落 kv（现场排查 + 重启后还在）
        } catch (Throwable t) {
            log(h, "档位层结算异常（不影响上面那一笔）：" + t);
        }
    }

    /**
     * 一条通知事件（戳一戳 / 撤回 / 进群 / 禁言…）→ <b>只记账</b>。
     *
     * <p><b>硬契约（基板定的，不是我定的）</b>：这条路只能记账 —— 落点是 {@code Sinks.SilentSink}，
     * {@code h.say()} 一个字都发不出去、也起不了一轮；置 {@code _handled} 没有任何效果。
     * 所以这里只做一件事：{@code _notice_sub_type} 是戳 ⇒ 档位机的戳计数 +1（规格 §3.2 通路 A）。</p>
     */
    private static void onNotice(JsonObject payload, Host h) {
        if (payload == null || h == null) return;
        EmotionCfg c = EmotionCfg.of(h);
        if (!c.flag("开关", true)) return;
        if (!c.flag("通知钩子", true)) return;
        Store s = store(h);
        if (s == null) return;
        // 本插件不提供工具（没有 op 可判）：自主钩子跑在 SYSTEM 主体上，写自己的表恒放行。
        try {
            EmotionMood.onNotice(s, c, h, payload);
            EmotionMood.flush(s);
        } catch (Throwable t) {
            log(h, "通知钩子结算异常：" + t);
        }
    }

    /**
     * 基板唯一 tick → 衰减 + 档位层的网格结算。
     *
     * <p>用 payload 里的 {@code ts}（基板同进程的时钟）；缺了就取本地时钟。
     * 结算口径见 {@link EmotionState#decayAll}：不管 tick 多快多慢，长期速率与 V3 的每分钟一致。
     * 档位层在这里做两件事：① 把"网格已经翻页"的场合显式结算回平淡（不必等下一条消息）；
     * ② 把攒着的转移日志落进 {@code kv}（重启后还能查"上一次状态为什么变"）。</p>
     */
    private static void onTimer(JsonObject payload, Host h) {
        if (h == null) return;
        EmotionCfg c = EmotionCfg.of(h);
        if (!c.flag("开关", true)) return;
        Store s = store(h);
        if (s == null) return;
        // 本插件不提供工具（没有 op 可判）：自主钩子跑在 SYSTEM 主体上，读写自己的表恒放行。
        long now = payload == null ? 0L : J.l(payload, "ts", 0L);
        // ★ 批 15 / 2026-09-26：基板 tick 没给 ts 时的兜底也走唯一接缝（默认 = 真实墙上时钟）。
        if (now <= 0L) now = EmotionMood.nowMs();
        int n = EmotionState.decayAll(s, c, now);
        try {
            int gridN = EmotionMood.onTimer(s, c, now);
            String gl = EmotionMood.gridLast();
            if (c.flag("诊断", false)) log(h, "tick 结算：" + gridN + " 个场合翻页");
            if (Str.has(gl)) log(h, gl);
            EmotionMood.flush(s);
        } catch (Throwable t) {
            log(h, "档位层 tick 异常：" + t);
        }
        if (n > 0 && c.flag("日志", false)) log(h, "衰减结算 " + n + " 行");
    }

    /**
     * 通知留痕兜底（规格 §3.2 <b>通路 B</b>）：读 {@code grouplog}/{@code dialog} 里
     * {@code content} 含戳标签的行。
     * <p>只在基板没有 {@code on_notice} 钩子（配置 {@code 通知钩子: false}）时才用 ——
     * 它比通路 A 晚（要等下一条消息），但不需要任何新基板改动，也能给探针造夹具。
     * 库没声明/读不动都当"没有"。</p>
     */
    private static List<JsonObject> pokeRows(Store s, long gid, long now, EmotionCfg c) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        try {
            String lib = gid > 0L ? "grouplog" : "dialog";
            JsonObject f = new JsonObject();
            if (gid > 0L) f.addProperty("group_id", gid);
            JsonObject gte = new JsonObject();
            gte.addProperty("ts", now - c.i("重置分钟", 20) * 60000L);
            f.add("_gte", gte);
            List<JsonObject> rows = s.list(lib, f, 200, "ts DESC");
            if (rows == null) return out;
            List<String> labels = c.tags("戳-标签");
            if (labels.isEmpty()) labels = EmotionCfg.split("戳一戳");
            for (JsonObject row : rows) {
                String content = J.s(row, "content", "");
                if (Str.blank(content)) continue;
                for (String lb : labels) {
                    if (Str.has(lb) && content.contains(lb)) {
                        out.add(row);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ================================================================ 小工具

    private static Store store(Host h) {
        if (h == null) return null;
        try {
            return h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这条消息"什么时候发生的"：优先用事件时间（秒），但与本地时钟相差超过「时钟容差秒」时
     * 以本地时钟为准 —— 事件时间由上游机器给，跨机部署时可能是别的钟。
     */
    private static long eventTime(Ev e, EmotionCfg c) {
        // ★ 批 15 / 2026-09-26：这一句是"本地现在"，必须与状态机同一个接缝 —— 否则注入/平移时钟时，
        //   事件时间（payload time，按接缝给）与本地钟（裸时钟）差得超过「时钟容差秒」⇒ 每次都退回
        //   裸本地钟，整条链又变回时钟相关。默认（没注入）= 真实墙上时钟，生产与改前逐字节相同。
        long local = EmotionMood.nowMs();
        long ev = 0L;
        try {
            ev = e.time() * 1000L;
        } catch (Throwable ignored) {
        }
        if (ev <= 0L) return local;
        long tol = c.i("时钟容差秒", 300) * 1000L;
        if (tol > 0L && Math.abs(ev - local) > tol) return local;
        return ev;
    }

    private static void log(Host h, String msg) {
        try {
            if (h == null) return;
            Out o = h.out();
            if (o != null) o.dim("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
