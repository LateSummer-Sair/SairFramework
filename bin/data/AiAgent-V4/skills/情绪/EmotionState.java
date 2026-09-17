import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.store.Store;

/**
 * 情绪状态：<b>自己的 {@code emotion} 表</b>的读写与那台状态机。
 *
 * <h3>隔离（"按人按群"）</h3>
 * <p>一行 = 一个「人在一个场合」。键是 {@code (scope, scope_id)}：</p>
 * <ul>
 *   <li>私聊：{@code scope='private'}，{@code scope_id=<QQ>}；</li>
 *   <li>群聊：{@code scope='group'}，{@code scope_id=<群号>:<QQ>}。</li>
 * </ul>
 * <p>于是 A 群里被骂出来的怒气<b>不会</b>跑到 B 群，也不会跑到私聊 —— V3 的全局怒气正是被
 * 这个"跨上下文串味"咬过（见 {@code EmotionStateManager.noteUnfriendlyGroup} 的注释），
 * V4 直接按"人在哪个场合"分开存。</p>
 *
 * <h3>写自己、只读别人</h3>
 * <p>本类只碰 {@code emotion} 这一张表（{@link Store} 的通用 CRUD）。
 * {@code memory} / {@code dialog} / {@code grouplog} / {@code favor} 一概<b>不写</b> ——
 * 要读好感档走 {@code Host.favor()}，要读别的库走 {@link Store#list}，写路径只有这一张表。</p>
 *
 * <h3>衰减（V3 的每秒衰减线程 → 基板唯一 tick）</h3>
 * <p>V3 每分钟减 {@code 怒气 2.0 / 伤心 1.5}；V4 的 tick 是 20 秒一次，所以这里按
 * <b>「距上次结算过了几个「衰减分钟」」</b>一次补上若干份（{@code decay_ms} 累计器），
 * 于是不管 tick 快慢，长期速率与 V3 一致，而且<b>消息来的时候先结算再累加</b>
 * （等于把 V3 那条衰减线程的效果补到位）。</p>
 */
public final class EmotionState {

    /** 本插件自己的表（表结构见 {@link EmotionSkill#declare}）。 */
    public static final String LIB = "emotion";

    /** 私聊场合。 */
    public static final String S_PRIVATE = "private";
    /** 群聊场合。 */
    public static final String S_GROUP = "group";

    /** 恋爱状态取值（结构性取值，不是文案；与 V3 的 lover/betrayer/ex 同义）。 */
    public static final String R_NONE = "none";
    public static final String R_LOVER = "lover";
    public static final String R_BETRAYER = "betrayer";
    public static final String R_EX = "ex";

    /** 每个键一把锁：同一个「人在一个场合」的「读-改-写」串行（两条会话道会同时进来）。 */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    private EmotionState() {}

    // ================================================================ 键

    /** 场合：群聊 = {@code group}，其余（私聊）= {@code private}。 */
    public static String scopeOf(long groupId) {
        return groupId > 0L ? S_GROUP : S_PRIVATE;
    }

    /** 场合号：群聊 = {@code <群号>:<QQ>}（同一个人在别的群是另一个人格），私聊 = {@code <QQ>}。 */
    public static String scopeIdOf(long qq, long groupId) {
        return groupId > 0L ? (groupId + ":" + qq) : String.valueOf(qq);
    }

    private static Object lockOf(String key) {
        String k = key == null ? "" : key;
        Object o = LOCKS.get(k);
        if (o != null) return o;
        Object fresh = new Object();
        Object prev = LOCKS.putIfAbsent(k, fresh);
        if (LOCKS.size() > 4096) LOCKS.clear();          // 长期运行的收敛（锁对象丢了也只是回到不串行）
        return prev == null ? fresh : prev;
    }

    // ================================================================ 状态

    /** 一行情绪状态（{@code emotion} 表的一行；{@code id=0} 表示还没落库）。 */
    public static final class St {
        public long id;
        public long ts;
        public String scope = S_PRIVATE;
        public String scopeId = "";
        public long groupId;
        public long qq;
        public double angry;
        public double sad;
        public int affinity;
        public String lastEmotion = "";
        public String romance = R_NONE;
        public long romanceId;
        public int day;
        public int hits;
        public long decayMs;
        /** 本次有没有"当天交流"（V3 的 addDailyAffection 口径）。 */
        public boolean dailyWanted;
        /** 本次命中的规则名（诊断，写进 extra）。 */
        public String matched = "";

        /** 唯一键（锁用）。 */
        public String key() { return scope + "|" + scopeId; }
    }

    private static St blank(String scope, String sid, long qq, long gid, long now, EmotionCfg c) {
        St st = new St();
        st.scope = scope;
        st.scopeId = sid;
        st.qq = qq;
        st.groupId = gid;
        st.ts = now;
        st.decayMs = now;
        st.affinity = c == null ? 0 : iclamp(c.i("亲疏初始", 0),
                c.i("亲疏下限", EmotionCfg.DEF_AFF_MIN), c.i("亲疏上限", EmotionCfg.DEF_AFF_MAX));
        return st;
    }

    /** 行 → 状态。 */
    private static St of(JsonObject r) {
        St st = new St();
        if (r == null) return st;
        st.id = J.l(r, "id", 0L);
        st.ts = J.l(r, "ts", 0L);
        st.scope = J.s(r, "scope", S_PRIVATE);
        st.scopeId = J.s(r, "scope_id", "");
        st.groupId = J.l(r, "group_id", 0L);
        st.qq = J.l(r, "qq", 0L);
        st.angry = J.d(r, "angry", 0.0d);
        st.sad = J.d(r, "sad", 0.0d);
        st.affinity = J.i(r, "affinity", 0);
        st.lastEmotion = J.s(r, "last_emotion", "");
        st.romance = J.s(r, "romance", R_NONE);
        st.romanceId = J.l(r, "romance_id", 0L);
        st.day = J.i(r, "day", 0);
        st.hits = J.i(r, "hits", 0);
        st.decayMs = J.l(r, "decay_ms", 0L);
        return st;
    }

    // ================================================================ 读

    /** 读一行（没有返回 null）。任何异常都吞掉当"没有这一行"。 */
    public static St load(Store s, String scope, String sid) {
        if (s == null || Str.blank(scope) || Str.blank(sid)) return null;
        try {
            JsonObject f = new JsonObject();
            f.addProperty("scope", scope);
            f.addProperty("scope_id", sid);              // scope_id 列是 TEXT
            List<JsonObject> rows = s.list(LIB, f, 1, "id DESC");
            if (rows == null || rows.isEmpty()) return null;
            return of(rows.get(0));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读当前会话/这个人对应的状态；<b>没有行时给一份空状态</b>（不落库）。
     * <p>注入用这一支：第一次说话也有一行"平静/陌生人"的态度可注入（V3 永远注入情绪块）。</p>
     */
    public static St peek(Store s, EmotionCfg c, long qq, long gid, long now) {
        String scope = scopeOf(gid);
        String sid = scopeIdOf(qq, gid);
        St st = load(s, scope, sid);
        if (st == null) st = blank(scope, sid, qq, gid, now, c);
        return st;
    }

    // ================================================================ 累积（on_message）

    /**
     * 一条入站消息的情绪累积。
     *
     * <p>顺序照 V3：<b>先结算衰减</b> → 命中规则改数值 → 当天交流的亲疏 → 落库。
     * 情绪标签口径也是 V3 的：{@code 情绪-} 那一族<b>先到者胜</b>（优先级由 prompt.md 的行序决定），
     * {@code 触发-}/{@code 正则-} 那一族<b>命中即生效</b>（可叠加）。</p>
     *
     * @return 本次的状态（{@code store} 为 null 时返回 null）
     */
    public static St apply(Store s, EmotionCfg c, long qq, long gid, String text, long now) {
        if (s == null || c == null || qq <= 0L) return null;
        String scope = scopeOf(gid);
        String sid = scopeIdOf(qq, gid);
        St st;
        boolean created;
        synchronized (lockOf(scope + "|" + sid)) {
            st = load(s, scope, sid);
            created = st == null;
            if (created) st = blank(scope, sid, qq, gid, now, c);
            settle(st, c, now);

            List<String> hit = new ArrayList<String>();
            boolean label = false;

            // ① 情绪标签族：先到者胜（V3 detectMessageEmotion：安慰 > 难过 > 生气 > 开心 > 撒娇 > 疲惫 > 调侃 > 感激 > 友善）
            List<EmotionCfg.Rule> labels = c.rules("情绪");
            for (EmotionCfg.Rule r : labels) {
                if (!r.matches(text)) continue;
                if (!r.gate(st.angry, st.sad, st.affinity, st.romance)) continue;
                effects(r, st, c, qq);
                st.lastEmotion = Str.has(r.mood) ? r.mood : r.name;
                hit.add(r.name);
                label = true;
                break;
            }
            // ② 事件族：命中即生效（V3 的 onInsult / onSexualHarassment：与标签族可以同时生效）
            List<EmotionCfg.Rule> events = new ArrayList<EmotionCfg.Rule>();
            events.addAll(c.rules("触发"));
            events.addAll(c.rules("正则"));
            for (EmotionCfg.Rule r : events) {
                if (!r.matches(text)) continue;
                if (!r.gate(st.angry, st.sad, st.affinity, st.romance)) continue;
                effects(r, st, c, qq);
                if (Str.has(r.mood)) st.lastEmotion = r.mood;
                hit.add(r.name);
            }
            // 没命中任何"对方情绪"标签 → 平静（V3 的 EMOTION_NEUTRAL 覆盖口径）
            if (!label) st.lastEmotion = c.s("平静标签", EmotionCfg.DEF_CALM);

            // 当天交流（V3 addDailyAffection）：① 命中了带「每日」的规则，或
            // ② 一条「对方情绪」标签都没命中（= V3 的 onNormalInteraction）。每天只加一次。
            if (c.flag("无标签也算交流", true) && !label) st.dailyWanted = true;
            if (st.dailyWanted) daily(st, c, now);

            st.hits = st.hits + hit.size();
            st.matched = Str.join(hit, ",");
            st.ts = now;
            save(s, st, created);
        }
        return st;
    }

    /** 命中规则 → 改状态（数值一律夹在 prompt.md 给的上下限里）。 */
    private static void effects(EmotionCfg.Rule r, St st, EmotionCfg c, long qq) {
        double aMax = c.d("怒气上限", EmotionCfg.DEF_ANGER_MAX);
        double sMax = c.d("伤心上限", EmotionCfg.DEF_SAD_MAX);
        if (!Double.isNaN(r.angrySet)) st.angry = clamp(r.angrySet, 0.0d, aMax);
        if (r.angryAdd != 0.0d) st.angry = clamp(st.angry + r.angryAdd, 0.0d, aMax);
        if (!Double.isNaN(r.sadSet)) st.sad = clamp(r.sadSet, 0.0d, sMax);
        if (r.sadAdd != 0.0d) st.sad = clamp(st.sad + r.sadAdd, 0.0d, sMax);
        int fMin = c.i("亲疏下限", EmotionCfg.DEF_AFF_MIN);
        int fMax = c.i("亲疏上限", EmotionCfg.DEF_AFF_MAX);
        if (r.affSet != Integer.MIN_VALUE) st.affinity = iclamp(r.affSet, fMin, fMax);
        if (r.affAdd != 0) st.affinity = iclamp(st.affinity + r.affAdd, fMin, fMax);
        if (r.daily) st.dailyWanted = true;
        if (Str.has(r.romance)) romance(r.romance, st, c, qq);
    }

    /**
     * 恋爱状态迁移（V3 的三道门照抄，且<b>只在恋爱状态下才可能被改写</b>）：
     * <ul>
     *   <li>{@code lover}（V3 tryConfess）：当前不是恋人、不是背叛者，且亲疏 ≥「恋爱门槛」；成功时亲疏置上限；</li>
     *   <li>{@code ex}（V3 normalBreakup）：当前恋人<b>正是这个人</b>；之后亲疏落到「分手亲疏」；</li>
     *   <li>{@code betrayer}（V3 onBetrayal）：同上；怒气置上限、亲疏置下限。</li>
     * </ul>
     */
    private static void romance(String target, St st, EmotionCfg c, long qq) {
        boolean isLover = R_LOVER.equals(st.romance);
        if (R_LOVER.equals(target)) {
            if (isLover || R_BETRAYER.equals(st.romance)) return;
            if (st.affinity < c.i("恋爱门槛", 800)) return;
            st.romance = R_LOVER;
            st.romanceId = qq;
            st.affinity = c.i("亲疏上限", EmotionCfg.DEF_AFF_MAX);
            return;
        }
        if (R_EX.equals(target) || R_BETRAYER.equals(target)) {
            if (!isLover || st.romanceId != qq) return;
            st.romance = target;
            if (R_BETRAYER.equals(target)) {
                st.angry = c.d("怒气上限", EmotionCfg.DEF_ANGER_MAX);
                st.affinity = c.i("亲疏下限", EmotionCfg.DEF_AFF_MIN);
            } else {
                st.affinity = c.i("分手亲疏", 100);
            }
            return;
        }
        if (R_NONE.equals(target)) {
            st.romance = R_NONE;
            st.romanceId = 0L;
        }
    }

    /** 当天首次交流的亲疏（V3 是每天 +2~3 随机；这里取 prompt.md 的定值 —— 注入段里不许有随机数）。 */
    private static void daily(St st, EmotionCfg c, long now) {
        int today = ymd(now);
        if (st.day == today) return;
        st.day = today;
        int gain = c.i("每日亲疏", EmotionCfg.DEF_DAILY_AFF);
        if (gain != 0) {
            st.affinity = iclamp(st.affinity + gain,
                    c.i("亲疏下限", EmotionCfg.DEF_AFF_MIN), c.i("亲疏上限", EmotionCfg.DEF_AFF_MAX));
        }
    }

    // ================================================================ 衰减（on_timer）

    /** 一次衰减结算（{@code on_timer} 的每拍；返回真的改到了几行）。 */
    public static int decayAll(Store s, EmotionCfg c, long now) {
        if (s == null || c == null) return 0;
        int batch = c.i("衰减批量", EmotionCfg.DEF_BATCH);
        if (batch <= 0) batch = EmotionCfg.DEF_BATCH;
        List<JsonObject> rows = new ArrayList<JsonObject>();
        rows.addAll(positive(s, "angry", batch));
        rows.addAll(positive(s, "sad", batch));
        if (rows.isEmpty()) return 0;
        Set<Long> seen = new HashSet<Long>();
        int changed = 0;
        for (JsonObject r : rows) {
            long id = J.l(r, "id", 0L);
            if (id <= 0L || !seen.add(Long.valueOf(id))) continue;
            St st = of(r);
            synchronized (lockOf(st.key())) {
                if (!settle(st, c, now)) continue;
                JsonObject patch = new JsonObject();
                patch.addProperty("angry", st.angry);
                patch.addProperty("sad", st.sad);
                patch.addProperty("decay_ms", st.decayMs);
                try {
                    if (s.update(LIB, id, patch)) changed++;
                } catch (Throwable ignored) {
                    // 单行写失败不影响其余行
                }
            }
        }
        return changed;
    }

    /** 取某一列 &gt; 0 的行（怒气/伤心分别一支，避免 OR 过滤）。 */
    private static List<JsonObject> positive(Store s, String col, int limit) {
        try {
            JsonObject inner = new JsonObject();
            inner.addProperty(col, 0.0001d);
            JsonObject f = new JsonObject();
            f.add("_gte", inner);
            List<JsonObject> rows = s.list(LIB, f, limit, "decay_ms ASC");
            return rows == null ? new ArrayList<JsonObject>() : rows;
        } catch (Throwable t) {
            return new ArrayList<JsonObject>();
        }
    }

    /**
     * 按「衰减分钟」补上若干份衰减（V3：怒气 -2.0/分钟、伤心 -1.5/分钟）。
     *
     * @return 真的变了吗（没变 = 不用写库）
     */
    private static boolean settle(St st, EmotionCfg c, long now) {
        double periodMin = c.d("衰减分钟", EmotionCfg.DEF_DECAY_MIN);
        long period = (long) Math.round(periodMin * 60000.0d);
        if (period <= 0L || now <= 0L) return false;
        long base = st.decayMs > 0L ? st.decayMs : st.ts;
        if (base <= 0L) {
            st.decayMs = now;
            return true;
        }
        if (now <= base) return false;                       // 时钟倒挂（事件时间比本地早）：不动
        long steps = (now - base) / period;
        if (steps <= 0L) return false;
        long cap = 100000L;                                  // 一次补太多没意义（0 是下限，夹一下防溢出）
        if (steps > cap) steps = cap;
        double beforeA = st.angry;
        double beforeS = st.sad;
        double aMax = c.d("怒气上限", EmotionCfg.DEF_ANGER_MAX);
        double sMax = c.d("伤心上限", EmotionCfg.DEF_SAD_MAX);
        st.angry = clamp(st.angry - steps * c.d("怒气衰减", EmotionCfg.DEF_ANGER_DECAY), 0.0d, aMax);
        st.sad = clamp(st.sad - steps * c.d("伤心衰减", EmotionCfg.DEF_SAD_DECAY), 0.0d, sMax);
        st.decayMs = base + steps * period;
        return st.angry != beforeA || st.sad != beforeS || st.decayMs != base;
    }

    // ================================================================ 写（只写自己的表）

    private static void save(Store s, St st, boolean created) {
        JsonObject row = new JsonObject();
        row.addProperty("ts", st.ts);
        row.addProperty("scope", st.scope);
        row.addProperty("scope_id", st.scopeId);
        row.addProperty("group_id", st.groupId);
        row.addProperty("qq", st.qq);
        row.addProperty("angry", st.angry);
        row.addProperty("sad", st.sad);
        row.addProperty("affinity", st.affinity);
        row.addProperty("last_emotion", st.lastEmotion);
        row.addProperty("romance", st.romance);
        row.addProperty("romance_id", st.romanceId);
        row.addProperty("day", st.day);
        row.addProperty("hits", st.hits);
        row.addProperty("decay_ms", st.decayMs);
        row.addProperty("extra", st.matched);
        try {
            if (created || st.id <= 0L) {
                long id = s.put(LIB, row);
                if (id > 0L) st.id = id;
            } else {
                s.update(LIB, st.id, row);
            }
        } catch (Throwable ignored) {
            // 写失败 = 这一次状态没记住（不影响这一轮对话）
        }
    }

    // ================================================================ 小工具

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        if (v < min) return min;
        return v > max ? max : v;
    }

    private static int iclamp(int v, int min, int max) {
        if (v < min) return min;
        return v > max ? max : v;
    }

    /** 哪一天（{@code yyyyMMdd}；"每天首次交流"的口径）。 */
    public static int ymd(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms <= 0L ? System.currentTimeMillis() : ms);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }
}
