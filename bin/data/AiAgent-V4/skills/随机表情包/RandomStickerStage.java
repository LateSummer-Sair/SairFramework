import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 随机表情包：按概率在回复<b>之后</b>追一条<b>独立的图片消息</b>（{@code [CQ:image,file=…]}），
 * 绝不与文字拼在同一条里。
 *
 * <h3>挂在哪</h3>
 * <p>{@link OutboundStage}，{@link #order()} = {@code 30}（排在 {@code 拟人化}(10) 与 {@code @提问者}(20) 之后）。
 * 只用 {@link #extra(Outbound)} 这一个口：
 * <ul>
 *   <li>它是<b>整批</b>级别的口（一次 {@code say} 只收一次），所以三段的回复不会追成三张表情包；</li>
 *   <li>基板保证"只在确实还有话要发的时候才收"（整条被否决 / 被洗成空 → 不调 {@code extra}），
 *       所以本插件<b>不自己再判一次正文</b>；</li>
 *   <li>追加的消息<b>不再过 stage</b>（防止插件互相追加），也不需要"与正文同一条"。</li>
 * </ul>
 * {@code veto} / {@code transform} <b>不实现</b>（保持默认：不否决、不改正文）。</p>
 *
 * <h3>数字与口径出处（V3 摘录：那两个文件<b>不随本工程提供</b>，下面的行号是 V3 当时的行号，只能当引用看）</h3>
 * <p>{@code sair/aiagent/onebot/QQAgentBridge.java}：</p>
 * <pre>
 *   :21   private static final double STICKER_RANDOM_PROBABILITY = 0.4;
 *   :640   if (!useExecs &amp;&amp; !messages.isEmpty() &amp;&amp; agentExecutor != null
 *                  &amp;&amp; agentExecutor.getStickerManager() != null) {
 *   :643       if (Math.random() &lt; STICKER_RANDOM_PROBABILITY) {          // ← 概率口径
 *   :644         StickerEntry match = agentExecutor.getStickerManager().randomSticker();
 *   :648         String localPath = match.getFilePath();
 *   :649         if (localPath != null &amp;&amp; !localPath.isEmpty()
 *   :650                 &amp;&amp; new java.io.File(localPath).exists()) {
 *   :651             cq = ToolDispatcher.buildImageCq(localPath);          // ← 本地存在就用本地
 *   :652         } else if (match.getImageUrl() != null &amp;&amp; !match.getImageUrl().isEmpty()) {
 *   :653             cq = ToolDispatcher.buildImageCq(match.getImageUrl()); // ← 否则回退原始 QQ URL
 *   :654         }
 *   :655         if (cq != null) {
 *   :657             messages.add(cq);                                      // ← 单独一条消息
 * </pre>
 * <p>三条口径一字不改：<b>概率 0.4</b>（{@code Math.random() < p}，本插件用 {@code RND.nextDouble() < p}，
 * 两者都是 {@code [0,1)} 上的同一种比较）、<b>本地优先</b>（本地路径存在才用它）、
 * <b>拿不到就什么都不发</b>（{@code cq == null} 时不 add）。
 * V3 那句注释的理由同样照抄：<i>图片单独作为一条消息发送，避免与文字混排导致突兀或发送失败</i>。</p>
 *
 * <h3>库存从哪来（只读）</h3>
 * <p>库 = 基板六库的 {@code sticker}（{@code id/ts/file/hash/scene/source/uses/extra}），
 * 只用 {@link Host#store()} 的 {@code count}/{@code list}，<b>不写库、不建表、不写 SQL、不改 uses</b>
 * （V3 的 {@code randomSticker()} 会 {@code incrementStickerUsage}，本插件<b>故意不做</b>：
 * 出站热路径上写库会拖慢每一轮对话，而且"谁改的"不该由一条追图负责）。</p>
 * <p><b>不把整个库拉进内存</b>：先 {@code count} 拿总数，再在 {@code id} 空间里随机取点、
 * 每次 {@code list(..., limit=1, ...)} 只看一行（最多 {@code 随机重试次数} 次）；
 * 抽样都拿不到可用行时才退到"有界窗口"扫描，窗口上限 = {@code 最多库存扫描}。</p>
 *
 * <h3>取图优先级（{@code 取图优先级: 本地优先}）</h3>
 * <ol>
 *   <li>{@code file} 是本地绝对路径（{@code C:\} / {@code C:/} / {@code \\\\srv\} / {@code /} / {@code file:///}）
 *       且<b>文件真的在</b> → 原样用（V3 第一步）；</li>
 *   <li>否则 {@code file} 本身是 {@code http(s)://} / {@code base64://} → 用它（V3 的"回退原始 QQ URL"）；</li>
 *   <li>否则看 {@code extra} 里留的 URL 键（默认 {@code url,image_url,imageUrl,origin,qq_url}）——
 *       "本地那副本已被清理、但原始 URL 还在"的等价物；</li>
 *   <li>否则 {@code file} 是别的非空值（NapCat fileid / 相对名）→ 由 {@code 允许fileid} 决定发不发；</li>
 *   <li>都不成立 → 静默不追加（V3：{@code cq == null} 不发）。</li>
 * </ol>
 * <p><b>不做</b>的事：不拼 URL、不起文件服务、不做 base64 —— NapCat 的本地文件改写是基板在
 * {@code Api.call} 里统一做的（{@code Api.Relay}），所以这里<b>只把值原样填进 {@code file=}</b>。</p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li>{@code m.host()} 可能为 null（探针直接注册的合成 stage 就是 null）→ 用兜底阈值，且因为拿不到
 *       {@code store()} 而<b>静默不追加</b>，绝不抛异常；{@code host().store()} 也可能为 null，同样容忍；</li>
 *   <li>{@code sticker} 库没被声明（插件「表情包」没装）→ {@code count} 回 0 → 静默；</li>
 *   <li>库存为空 / 字段全空 / 字段脏（带换行、{@code [}、{@code ]}）→ 静默不追加；</li>
 *   <li>拼出来的 CQ 超过 {@code 图片最大字符}（默认 500）→ 不追加：追加的消息与正文共用同一把分段尺子
 *       （{@code replyMaxChars}=1000、群聊 {@code replyGroupMaxChars}=180），半条 {@code [CQ:image,…]} 比不追更糟；</li>
 *   <li>{@code extra()} 最外层还有一层 {@code catch (Throwable)} → 返回空表，一轮对话绝不会因本插件挂掉。</li>
 * </ul>
 */
public class RandomStickerStage implements OutboundStage {

    // ==================== 结构性常量（不是文案，不进 prompt.md） ====================

    /** 六库里的表情包库（表名，结构性命名的唯一来源）。 */
    private static final String LIB = "sticker";
    /** 基板 {@code Store} 的取值范围上限（窗口一次不能要得比它更大）。 */
    private static final int STORE_MAX = Store.MAX_LIMIT;

    // ==================== 兜底默认值（真正生效的值在 prompt.md；host 为 null 时用它们） ====================

    /** V3 原样（{@code QQAgentBridge} 的 {@code STICKER_RANDOM_PROBABILITY}）：出厂 {@code 0.4}。 */
    private static final double DEF_PROB = 0.4d;
    /** 随机 id 取点不中时的重试次数（每次只看一行）。 */
    private static final int DEF_RETRY = 6;
    /** 有界窗口最多看多少行（"别把整个库拉进内存"的上限）。 */
    private static final int DEF_SCAN_MAX = 200;
    /** V3 口径：本地存在的路径优先。 */
    private static final String DEF_PRIORITY = "本地优先";
    /** 拿不到就什么都不发，且不打日志。 */
    private static final String DEF_EMPTY = "静默";
    /** NapCat 认得 fileid，基板原样透传，所以默认允许。 */
    private static final boolean DEF_ALLOW_FILEID = true;
    /** 只在有 QQ 落点（群/私聊）时才追；控制台没有图片通道。 */
    private static final boolean DEF_QQ_ONLY = true;
    /** 图片模板（V3 {@code ToolDispatcher.buildImageCq} 的形状）。 */
    private static final String DEF_TPL = "[CQ:image,file={file}]";
    /**
     * 追加消息的口令上限（字符）。超过就不发。
     * <p>理由：基板口径是"追加的消息与正文<b>共用同一把分段尺子</b>"（{@code replyMaxChars}=1000、
     * 群聊 {@code replyGroupMaxChars}=180），一条被劈成两半的 {@code [CQ:image,…]} 是坏消息；
     * 而且超长 base64 交给 QQ 本身也容易被拒。所以宁可这一轮不追，也不发半条 CQ。
     * 500 落在"V3 单条上限 1000"和"群聊 180"的中间偏安全一侧。</p>
     */
    private static final int DEF_MAX_CQ = 500;
    /** {@code extra} 里可能留着原始 URL 的键（逗号分隔）。 */
    private static final String DEF_URL_KEYS = "url,image_url,imageUrl,origin,qq_url";
    /** 追加成功后的控制台行（V3 有一行 {@code [QQMsg] Sticker #<id> (random) sent as separate msg}）。 */
    private static final String DEF_LOG =
            "[sticker] 追加表情包 id=#{id} scene={scene} file={file} session={session}";
    /** 没挑到可发行时的那一行（只在「库存为空时: 打日志」时打）。 */
    private static final String DEF_EMPTY_LOG = "[sticker] 没挑到可发的表情包（库存空 / 字段全空）—— 本轮不追加";
    /** 「库存为空时」的静默取值。 */
    private static final String EMPTY_SILENT = "静默";

    /** 随机数：V3 用 {@code Math.random()}（同一个 {@code java.util.Random} 语义），这里显式留一个实例。 */
    private static final Random RND = new Random();

    // ==================== OutboundStage ====================

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<String> extra(Outbound m) {
        try {
            if (m == null) return none();

            // 宿主可能为 null：阈值退回兜底值（概率照样掷），库存拿不到 → 静默不追加。
            Host h = m.host();
            Map<String, List<String>> c = Cfg.of(h);

            if (!flag(c, "开关", true)) return none();

            if (flag(c, "仅QQ落点", DEF_QQ_ONLY) && m.target() <= 0L) return none();   // 只进控制台：没有图片通道

            double p = dbl(c, "概率", DEF_PROB);
            if (!(p > 0d)) return none();                                   // 概率 0 = 关掉（不掷骰子）
            // V3: if (Math.random() < STICKER_RANDOM_PROBABILITY)
            boolean hit = p >= 1d || RND.nextDouble() < p;
            if (!hit) return none();

            if (h == null) return none();                                   // 拿不到宿主 = 拿不到库存
            Store store = h.store();
            if (store == null) return none();

            List<String> urlKeys = csv(one(c, "URL键", DEF_URL_KEYS));
            String prio = Str.trim(one(c, "取图优先级", DEF_PRIORITY));
            boolean allowFid = flag(c, "允许fileid", DEF_ALLOW_FILEID);

            Picked sel = pick(store, c, prio, allowFid, urlKeys);
            if (sel == null || Str.blank(sel.file)) {                       // 库存空 / 字段全空 → 静默
                if (!EMPTY_SILENT.equals(Str.trim(one(c, "库存为空时", DEF_EMPTY)))) {
                    log(h, one(c, "空日志", DEF_EMPTY_LOG));
                }
                return none();
            }

            // 选中本地绝对路径：先判"当前主体能不能读这个文件"再追加。
            // 判位主体是**当轮说话人**（外向扩展点由 ExtRegistry 的 guarded 绑当轮 caller；
            // Host.subject() 就是 Ctx.caller()），不是 SYSTEM ⇒ 回复某人的那条路上按那个人的位判：
            // 数据根 files/ 树（属 C、无专有位 ⇒ 非主人只读 R）读得到；**数据根外的本地路径（A 类）对普通用户会被拒**
            // （Acl.DEF_A 一位都不给）；只有没有触发者的路径才落到 Caller.systemActor（她自己的自主行为）。
            if (isLocalForm(sel.file)) {
                String deny = h.needPath(sel.file, 'R');
                if (deny != null) {
                    log(h, "追加表情包被拦：" + deny);
                    return none();
                }
            }

            String cq = Str.trim(fill(one(c, "图片模板", DEF_TPL), "file", sel.file));
            if (Str.blank(cq)) return none();
            // 太长会被分段尺子劈成两条（追加消息与正文共用同一把尺子）→ 这一轮不追，绝不发半条 CQ。
            if (cq.length() > atLeast(num(c, "图片最大字符", DEF_MAX_CQ), 16)) return none();

            List<String> out = new ArrayList<String>(1);
            out.add(cq);                                                    // V3 :657 messages.add(cq) —— 单独一条
            if (flag(c, "打日志", true)) {
                log(h, fill(one(c, "追加日志", DEF_LOG),
                        "id", String.valueOf(sel.id),
                        "scene", sel.scene,
                        "file", sel.file,
                        "session", Str.nz(m.session()),
                        "group", String.valueOf(m.target()),
                        "total", String.valueOf(m.total())));
            }
            return out;
        } catch (Throwable t) {
            return none();                                                  // 插件绝不拖垮一轮对话
        }
    }

    /** 空表（"不追加"的唯一表示）。 */
    private static List<String> none() { return Collections.emptyList(); }

    // ==================== 挑一张（只读、有界） ====================

    /** 挑中的一行：要发出去的 file 值 + 打日志用的两个字段。 */
    private static final class Picked {
        final String file;
        final long id;
        final String scene;

        Picked(String file, long id, String scene) {
            this.file = file;
            this.id = id;
            this.scene = scene == null ? "" : scene;
        }
    }

    /**
     * 从 {@code sticker} 库里挑一张可发的。
     *
     * <p>口径：① 库存为空 → {@code null}（静默）；
     * ② 在 {@code id} 空间里随机取点，每次 {@code LIMIT 1} 只看一行（最多 {@code 随机重试次数} 次）；
     * ③ 还是没挑到 → 有界窗口（最多 {@code 最多库存扫描} 行）里随机挑一张可发的。
     * 全程只有 {@code count}/{@code list}，只看 {@code limit} 行，绝不整库入内存。</p>
     */
    private static Picked pick(Store store, Map<String, List<String>> c,
                               String prio, boolean allowFid, List<String> urlKeys) {
        long total = store.count(LIB, null);
        if (total <= 0L) return null;                                       // 库存为空 → 静默（V3：拿不到就不发）

        // ② 随机 id 取点（id 空间均匀，不是行数均匀：有空洞时略偏，但只看 1 行、不进内存）
        long[] range = idRange(store);
        if (range != null) {
            int tries = (int) Math.min((long) atLeast(num(c, "随机重试次数", DEF_RETRY), 1), total);
            for (int i = 0; i < tries; i++) {
                long id = pickId(range[0], range[1]);
                List<JsonObject> rows = store.list(LIB, gteId(id), 1, "id ASC");
                Picked p = firstPicked(rows, prio, allowFid, urlKeys);
                if (p != null) return p;
            }
        }

        // ③ 有界窗口兜底
        int scanMax = atLeast(num(c, "最多库存扫描", DEF_SCAN_MAX), 1);
        if (scanMax > STORE_MAX) scanMax = STORE_MAX;
        List<JsonObject> win = store.list(LIB, null, scanMax, "id DESC");
        List<Picked> ok = new ArrayList<Picked>();
        if (win != null) {
            for (JsonObject r : win) {
                Picked p = picked(r, prio, allowFid, urlKeys);
                if (p != null) ok.add(p);
            }
        }
        if (ok.isEmpty()) return null;
        return ok.get(RND.nextInt(ok.size()));
    }

    /** {@code id} 的两端（各 LIMIT 1）；拿不到返回 null。 */
    private static long[] idRange(Store store) {
        List<JsonObject> lo = store.list(LIB, null, 1, "id ASC");
        List<JsonObject> hi = store.list(LIB, null, 1, "id DESC");
        if (lo == null || lo.isEmpty() || hi == null || hi.isEmpty()) return null;
        long a = J.l(lo.get(0), "id", 0L);
        long b = J.l(hi.get(0), "id", 0L);
        if (a <= 0L || b < a) return null;
        return new long[]{a, b};
    }

    /** {@code [lo, hi]} 上的一个随机 id（{@code nextDouble} 是 {@code [0,1)}）。 */
    private static long pickId(long lo, long hi) {
        if (hi <= lo) return lo;
        long span = hi - lo + 1L;
        if (span <= 0L) return lo;                                          // 溢出的极端情况：退回左端
        long off = (long) (RND.nextDouble() * (double) span);
        if (off < 0L) off = 0L;
        if (off >= span) off = span - 1L;
        return lo + off;
    }

    /** {@code {"_gte":{"id":x}}}：只看 id 不小于 x 的第一行（{@code Store.filter} 的控制键）。 */
    private static JsonObject gteId(long id) {
        JsonObject inner = new JsonObject();
        inner.addProperty("id", Long.valueOf(id));
        JsonObject f = new JsonObject();
        f.add("_gte", inner);
        return f;
    }

    /** 一批行里第一个可发的。 */
    private static Picked firstPicked(List<JsonObject> rows, String prio, boolean allowFid, List<String> urlKeys) {
        if (rows == null) return null;
        for (JsonObject r : rows) {
            Picked p = picked(r, prio, allowFid, urlKeys);
            if (p != null) return p;
        }
        return null;
    }

    /** 一行 → 可发的（拿不到返回 null）。 */
    private static Picked picked(JsonObject row, String prio, boolean allowFid, List<String> urlKeys) {
        String file = fileOf(row, prio, allowFid, urlKeys);
        if (Str.blank(file)) return null;
        return new Picked(file, row == null ? 0L : J.l(row, "id", 0L),
                row == null ? "" : J.s(row, "scene", ""));
    }

    // ==================== 取图优先级（V3 :648-654） ====================

    /**
     * 一行的 {@code file} 值 → 真正要填进 {@code file=} 的值（拿不到返回 null）。
     *
     * <p>V3 两步：本地路径存在 → 用它；否则原始 QQ URL → 用它；都没有 → 什么都不发。
     * 本插件把这两步映射到 {@code sticker} 行的 {@code file} + {@code extra} 上，并按
     * {@code 取图优先级} 排列候选。</p>
     */
    private static String fileOf(JsonObject row, String prio, boolean allowFid, List<String> urlKeys) {
        if (row == null) return null;
        String raw = Str.trim(J.s(row, "file", ""));
        String local = null;        // 本地绝对路径 + 文件真的在（V3 第一步）
        String url = null;          // file 本身就是 URL（V3 的"原始 QQ URL"）
        String fid = null;          // 别的非空值（NapCat fileid / 相对名）：基板原样透传
        if (!raw.isEmpty() && cqSafe(raw)) {
            File f = asFile(raw);
            if (f != null) {
                if (exists(f)) local = raw;                                 // V3 :649-651
            } else if (isUrlForm(raw)) {
                url = raw;
            } else if (allowFid) {
                fid = raw;
            }
        }
        String fromExtra = urlInExtra(row, urlKeys);                        // 本地那副本没了 → 回退原始 URL
        if ("URL优先".equals(prio)) return first(url, fromExtra, local, fid);
        if ("仅本地".equals(prio)) return first(local);
        if ("仅URL".equals(prio)) return first(url, fromExtra);
        return first(local, url, fromExtra, fid);                           // 默认 / 不认识的值 = 本地优先
    }

    private static String first(String... cands) {
        if (cands == null) return null;
        for (String s : cands) if (!Str.blank(s)) return s;
        return null;
    }

    /** 本地绝对路径形态（含 {@code file:///} 的基板同机口径）。 */
    private static boolean isLocalForm(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.isEmpty()) return false;
        if (s.length() >= 2 && s.charAt(1) == ':') return true;             // C:\x / C:/x
        if (s.startsWith("\\\\")) return true;                              // \\server\share
        if (s.startsWith("//")) return true;                                // //server/share
        if (s.startsWith("/")) return true;                                 // /x（POSIX 绝对路径）
        return s.toLowerCase().startsWith("file:///");
    }

    /** 本地路径形态 → File（只看形态，不看在不在）。 */
    private static File asFile(String v) {
        if (!isLocalForm(v)) return null;
        try {
            String s = v.trim();
            if (s.toLowerCase().startsWith("file:///")) s = s.substring("file:///".length());
            if (s.isEmpty()) return null;
            return new File(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code http(s)://} / {@code base64://}（V3 交给 {@code buildImageCq} 的那两种形态）。 */
    private static boolean isUrlForm(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("base64://");
    }

    /** 文件在不在（任何异常都当"不在"，V3 :650 的 {@code new File(p).exists()}）。 */
    private static boolean exists(File f) {
        try {
            return f != null && f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code extra} 里留着的原始 URL（本地那副本被清理之后的回退）。 */
    private static String urlInExtra(JsonObject row, List<String> keys) {
        try {
            JsonElement e = J.get(row, "extra");
            if (e == null || e.isJsonNull()) return null;
            if (e.isJsonPrimitive()) {
                String v = Str.trim(e.getAsString());
                return (isUrlForm(v) && cqSafe(v)) ? v : null;
            }
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            if (keys == null) return null;
            for (String k : keys) {
                if (Str.blank(k)) continue;
                String v = Str.trim(J.s(o, k, ""));
                if (!v.isEmpty() && isUrlForm(v) && cqSafe(v)) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * CQ 值安全：不含换行 / 控制字符 / {@code [} / {@code ]}。
     * <p>库存里的脏值（比如一条被截断的路径）会让整条消息拼坏，那种值一律不发。</p>
     */
    private static boolean cqSafe(String v) {
        if (v == null || v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < ' ' || ch == '[' || ch == ']') return false;
        }
        return true;
    }

    // ==================== 控制台一行（V3 也有一行） ====================

    private static void log(Host h, String line) {
        if (h == null || Str.blank(line)) return;
        try {
            Out o = h.out();
            if (o != null) o.dim(line);
        } catch (Throwable ignored) {
            // 打日志失败不影响这条消息
        }
    }

    // ==================== prompt.md（惰性缓存 + 按 mtime 失效） ====================

    /**
     * 本插件目录下 {@code prompt.md} 的键值表。
     * <p>读不到文件 / {@code m.host()} 为 null（探针直接注册的合成 stage 就是 null）→ 返回空表
     * （调用方逐项落到兜底默认值）。缓存按「文件 + mtime」失效：每条消息不读盘，改了文件下一条生效。</p>
     */
    private static final class Cfg {

        static final Map<String, List<String>> EMPTY = Collections.emptyMap();

        private static File file;
        private static long stamp = -1L;
        private static Map<String, List<String>> kv = new LinkedHashMap<String, List<String>>();

        static synchronized Map<String, List<String>> of(Host h) {
            if (h == null) return EMPTY;
            try {
                File dir = h.skillDir();
                if (dir == null) return EMPTY;
                File f = new File(dir, "prompt.md");
                boolean exists = f.isFile();
                long m = exists ? f.lastModified() : 0L;
                if (file != null && file.equals(f) && m == stamp) return kv;
                Map<String, List<String>> next = exists ? parse(f) : new LinkedHashMap<String, List<String>>();
                file = f;
                stamp = m;
                kv = next;
                return next;
            } catch (Throwable t) {
                return EMPTY;
            }
        }

        /** {@code 键: 值} 一行一条；{@code #} 开头是注释；行内第一个（半角/全角）冒号是分隔符。 */
        private static Map<String, List<String>> parse(File f) {
            Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                    String s = line.trim();
                    if (s.isEmpty() || s.charAt(0) == '#') continue;
                    int a = s.indexOf(':');
                    int b = s.indexOf('：');
                    int k = (a < 0) ? b : (b < 0 ? a : Math.min(a, b));
                    if (k <= 0) continue;
                    String key = s.substring(0, k).trim();
                    String val = s.substring(k + 1).trim();
                    if (key.isEmpty()) continue;
                    List<String> one = m.get(key);
                    if (one == null) {
                        one = new ArrayList<String>();
                        m.put(key, one);
                    }
                    one.add(val);
                }
            } catch (Throwable ignored) {
                // 读失败 = 空表 = 全用兜底值
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return m;
        }

        private Cfg() {}
    }

    private static String one(Map<String, List<String>> c, String key, String def) {
        if (c == null) return def;
        List<String> l = c.get(key);
        if (l == null || l.isEmpty()) return def;
        String v = l.get(0);
        return v == null ? def : v;
    }

    private static int num(Map<String, List<String>> c, String key, int def) {
        String v = one(c, key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 小数概率：{@code 0.4}（0~1 的小数）/ {@code 40%} / 裸百分数 {@code 40} 都认；
     * 读不出来就是兜底值，最后一律夹到 {@code [0,1]}。
     */
    private static double dbl(Map<String, List<String>> c, String key, double def) {
        String v = one(c, key, null);
        if (v == null) return def;
        try {
            String s = v.trim();
            boolean pct = s.endsWith("%");
            if (pct) s = s.substring(0, s.length() - 1).trim();
            double d = Double.parseDouble(s);
            if (pct || (d > 1d && s.indexOf('.') < 0)) d = d / 100d;        // 40% / 40 → 0.4
            if (d < 0d) return 0d;
            if (d > 1d) return 1d;
            return d;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean flag(Map<String, List<String>> c, String key, boolean def) {
        String v = one(c, key, null);
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                || s.equals("开") || s.equals("是")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                || s.equals("关") || s.equals("否")) return false;
        return def;
    }

    /** 逗号分隔 → 去空白、去空项（键表这类小清单用）。 */
    private static List<String> csv(String v) {
        if (Str.blank(v)) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (String s : Arrays.asList(v.split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int atLeast(int v, int min) { return v < min ? min : v; }

    /** {@code {键}} 字面替换（不用正则，值里带 {@code $} 也不怕）。 */
    private static String fill(String tpl, String... kv) {
        String s = tpl == null ? "" : tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = kv[i];
            String v = kv[i + 1];
            s = s.replace("{" + k + "}", v == null ? "" : v);
        }
        return s;
    }
}
