import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sair.v4.ai.DeepSeek;
import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.Http;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.qq.Ev;
import sair.v4.qq.Relay;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 表情包的<b>自动收集</b>（外挂：只属于 {@code 表情包} 技能，基板零改动）。
 *
 * <p>拆两段跑，因为两段的成本量级差三个数量级：</p>
 * <pre>
 *   on_timer（本技能<b>唯一</b>的钩子：每轮一次 kv 扫描 + 网络 I/O）
 *   <b>看图手（辅助识图）看图时</b> ──► kv sticker:cand/&lt;md5&gt; ──► 逐条：读到即删键 → 按候选行判定 →
 *   （跨技能接口，生产者 = 辅助识图）                            查 hash → 下载 → 魔数/内容 MD5 →
 *                                                               写索引 → 入库 → 磁盘账
 * </pre>
 *
 * <p><b>为什么只剩 {@code on_timer}</b>（甲方 2026-09-17 急令原话："表情包收集是跟着看图手走的，
 * 是在看图时的一个顺带，而不是主动自动去监听每一个消息"）：本技能<b>不再挂 {@code on_message}</b>、
 * 也<b>不再对任何入站消息做判断</b>。入站那一段（原来的 {@link #capture}）<b>已停用</b>：
 * 方法体只剩一个恒 {@code return}（签名留着，免得别处引用编译不过），且 {@code StickerSkill.on()}
 * 里那条 {@code "on_message"} 分支**已整条删掉** ⇒ 就算将来有人再调它，也什么都发生不了。
 * 收集的<b>唯一</b>来源 = 看图手在"看图"时写下的候选行。</p>
 *
 * <h3>判定来源：候选行（这条路上<b>一次视觉调用都没有</b>）</h3>
 * <ul>
 *   <li>候选行由 <b>{@code 辅助识图}</b>（看图手）在它看图时写下：键 {@code sticker:cand/&lt;md5&gt;}，
 *       {@code md5 = Fs.md5(Utf8(url 非空 ? url : file))}（＝本类 {@code srcMd5} 的<b>同一配方</b>）；
 *       值（键序固定）：{@code v,src,url,file,faces,text_len,safe,desc,id,model,ts}；</li>
 *   <li><b>收</b>：{@code safe==true} <b>且</b> {@code faces=="一张"} <b>且</b> {@code text_len < 15}
 *       （{@link #TEXT_MAX}）；<b>否则丢弃该行</b>（删键 + 一行可观测日志，<b>不静默</b>）；</li>
 *   <li>判定结果落到同一份<b>索引内容</b>：{@code extra.index = {faces,text_len,is_sticker,safe,desc,tags,verdict}}
 *       —— <b>形状与旧口径一个字不变</b>，只是字段来源从"视觉审查"换成"候选行"；{@code tags} /
 *       {@code verdict} 候选行不给就留空字符串（<b>不编内容</b>）；</li>
 *   <li><b>零视觉调用</b>：判定由候选行给出，落库路径是 {@link #processWithVerdict}（判定由调用方传入）。
 *       旧的 {@link #process} 已<b>停用</b>（恒拒，不下载也不入库），{@link #reviewSafe} 保留原文但
 *       <b>零调用者</b>（不可达）⇒ 整条心跳路径上没有任何 {@code h.ai()}。</li>
 * </ul>
 *
 * <h3>收集面：群 + 私聊（<b>入站那一侧现在归生产者</b>，本类只按候选行收）</h3>
 * <p>候选行<b>不带会话面</b>（接口冻结：{@code v,src,url,file,faces,text_len,safe,desc,id,model,ts}）
 * ⇒ 消费侧对群图与私聊图<b>一视同仁</b>：同一次下载 → 魔数 → 内容 MD5 → L3 内容去重、
 * 同一份 {@code extra.index} 索引内容、同一个 {@code sticker} 表；
 * 60 张纯滚动池（{@code ts ASC} 最旧先走）、15 天年龄上限、保底 15 张<b>全部原样生效</b> ——
 * <b>这一轮没有动池子与发送侧的一个字节</b>。</p>
 * <ul>
 *   <li><b>旧值 → 新值</b>：群那一支原来由 <b>{@code 收集范围}</b> + {@code 收集群白名单} 决定 ——
 *       现在<b>不再生效</b>（那条判断在 {@link #capture} 里，已停用）；
 *   <li><b>旧值 → 新值</b>：私聊那一支原来由 <b>{@code 收集私聊: 开|关}</b> 决定 —— 现在<b>不再生效</b>
 *       （同上）；{@code prompt.md} 的这两项配置<b>保留不动</b>（读值与状态 kv 的 {@code collect_private}
 *       / {@code private_note} 照旧写，便于回头看"当时配的是什么"），但<b>不再决定收不收</b>。
 *       <b>为什么</b>：候选行里没有群号/QQ（接口冻结），消费侧无从判断；而按甲方令，入站判断整条不再属于本技能。</li>
 * </ul>
 *
 * <h3>她自己的图永不入库（<b>哪一道在哪一侧生效</b>）</h3>
 * <ol>
 *   <li><b>入站侧（生产者 = {@code 辅助识图} 的看图手）</b>：{@code message_sent} / {@code extra.self}
 *       / {@code user_id == self_id} 三道 —— 这是原 {@link #capture} 的三道口径，
 *       现在<b>由生产者在写候选行之前判</b>（它才看得见入站事件）；</li>
 *   <li><b>消费侧（本类，仍在，仍然生效）</b>：
 *       <ul>
 *         <li><b>结构性检查</b>：候选行<b>自带</b> {@code self=true}（或 {@code extra.self=true} 那种形状，
 *             走本文既有的 {@link #selfMarked}）⇒ <b>直接跳过并删键</b>，一行日志（{@code cand_self}）；
 *             字段以生产者为准：<b>候选行没有这个字段就不猜</b>（不因为"没有"就拒，也不因为"没有"就当合格）；</li>
 *         <li><b>落库前</b>的同一血统：{@code extra.added_by} 仍写候选行给的发送者（{@code u}，没有则 0），
 *             与旧口径同一支（旧写法写的是 {@code J.l(e,"u",0L)}）。</li>
 *       </ul></li>
 * </ol>
 * <p>⇒ 三道防线<b>一道都没删</b>：① 三道判据搬到入站侧（生产者），②③ 消费侧的 self 字段检查 +
 * 落库前血统照旧。<b>{@code KV_SEEN}（L1 已见闸）随 {@link #capture} 一起停用</b>
 * （它只有那一个写者与读者）；同一张图被重复看到时由 L3 内容去重兜住（同内容只有一行、不重复入库）。</p>
 *
 * <h3>会话键：{@code sess}（<b>候选行不带它</b>）</h3>
 * <p>= {@code Caller.session()} 的形状（群 {@code group:<群号>}、私聊 {@code qq:<QQ>}）。
 * <b>旧值 → 新值</b>：原来队列行<b>总有</b>这一个自有字段，入库时原样写进 {@code extra.sess}；
 * 现在候选行<b>没有</b> {@code sess}（接口冻结的 11 个键里没有它）⇒ 消费侧造出来的行也不带它，
 * <b>新入库的行不会有 {@code extra.sess}</b>。<b>为什么</b>：① 接口冻结；② 候选行里没有群号/QQ；
 * ③ 消费侧在 {@code on_timer} 里问 {@code h.caller()} 拿到的是定时主体（{@code console}）——
 * 现问会把每一张都写成 {@code console}，比不写更坏。</p>
 * <p>读 {@code sess} 的<b>代码照旧保留</b>（{@link #processWithVerdict} 里仍是
 * {@code J.s(e,"sess","")}，缺 {@code sess} 时按 {@code extra.g} 兜底成 {@code group:<群号>}）——
 * 老行、手工塞的行、L3 命中补 {@code extra} 的那一支都还走这条。<b>淘汰判定（{@code ts ASC}）
 * 不看 {@code sess}</b>，发送侧也不看 ⇒ 池子与发图不受影响。</p>
 *
 * <h3>三层去重（构造性，不靠模型纪律）</h3>
 * <table border="1">
 *   <tr><th>层</th><th>键</th><th>放哪</th><th>代价</th></tr>
 *   <tr><td>L1 已见闸</td><td>URL/file 的 MD5</td><td>kv {@code sticker:collect:seen:&lt;srcMd5&gt;} —— <b>随 capture 停用</b>（无写者、无读者）</td><td>0</td></tr>
 *   <tr><td>L2 候选键唯一</td><td>候选键本身就是 {@code md5(url/file)}</td><td>kv {@code sticker:cand/&lt;md5&gt;}（生产者写、消费者<b>读到即删</b>）+ 每轮内存集合</td><td>0</td></tr>
 *   <tr><td>L3 <b>内容去重</b></td><td>下载后的字节 MD5</td><td>{@code sticker.hash} 列 + 现成索引 {@code idx_sticker_hash}</td><td>1 次 list</td></tr>
 * </table>
 * <p>L3 命中的那一条<b>只补 {@code extra}</b>（行不动、不重复插）；这条路上本来就没有"花钱审查"这一步了。</p>
 *
 * <h3>落盘与容量</h3>
 * <ul>
 *   <li>图片目录 <b>{@code <dataDir>/files/sticker/}</b>（新目录，与 V3 遗留的 {@code files/stickers/} 分开，不碰存量）；</li>
 *   <li>文件名 = <b>内容 MD5 前 16 位</b> + 后缀（后缀取自魔数，兜底 URL 扩展名）⇒ 天然幂等、不撞名；</li>
 *   <li>先下到 {@code dl-<src16>.part}，<b>体积 / 魔数 / 内容哈希 / 候选判定全过了才改名成正式名</b>
 *       ⇒ 被拒的图在盘上不留孤儿；</li>
 *   <li>磁盘账：超 {@code 磁盘上限MB} 时先删孤儿文件，再按对应行的 {@code ts ASC}（最旧的先走）删
 *       （<b>行保留</b>，{@code file} 退化为 {@code extra.url}，发送侧有回退），最后按文件时间兜底
 *       ⇒ <b>永不超上限</b>。</li>
 * </ul>
 *
 * <h3>发图侧（甲方的规则）</h3>
 * <p>{@code send} 没给 {@code scene} 时，先按<b>当前心情与现状</b>去匹配索引内容（情绪事实来自
 * {@code 情绪} 技能写的 {@code emotion} 表，这是跨技能<b>唯一能被读到</b>的情绪面），
 * 匹配不到则<b>随机</b>（照 V3 的发图机制）。匹配全是字面匹配（精确 scene → 基板 LIKE → {@code extra} 片段），
 * <b>不做模型判断</b>（要判断就由模型先决定 scene 再调）。</p>
 */
public final class StickerCollect {

    private StickerCollect() {}

    /** 六库里的表情包库（与 {@code StickerSkill.LIB} 同值；独立写一份，免得跨类耦合常量）。 */
    static final String LIB = "sticker";    /** 自动收集入库的 source 值 ⇒ 不加列也能把"自动收的"与手工收的分开。 */
    static final String SRC_AUTO = "auto:sticker-collect";
    /** 情绪状态的库名（{@code 情绪} 技能声明的；只读）。 */
    static final String LIB_EMOTION = "emotion";
    /** 图片目录名（{@code <files>/sticker}，**不是** V3 遗留的 {@code files/stickers}）。 */
    static final String DIR = "sticker";
    /**
     * 队列文件名（JSONL，一行一个候选）。
     *
     * <p><b>⚠ 这条路的生产者已停用</b>（{@link #capture} 不再写一行，本类里也没有任何"新写入队列"的路径）：
     * {@code sweep} 里仍然<b>排空</b>它，唯一目的是"清理历史上残留的候选，别让它们永远卡着"，
     * 排空 = <b>整批丢弃 + 一行日志</b>（不下载、不判定、不入库）。</p>
     */
    static final String QUEUE = "queue.jsonl";
    /** 未完成下载的临时后缀。 */
    static final String PART = ".part";

    /** L1 已见闸 / L2 用的 kv 前缀。<b>随 {@link #capture} 停用</b>（现在无写者、无读者）。 */
    static final String KV_SEEN = "sticker:collect:seen:";
    /** 视觉审查结果缓存前缀（用途前缀防串扰，照 V3 的经验 P6）。 */
    static final String KV_VISION = "sticker:collect:vision:";
    /** 上次真正 sweep 的时间戳。 */
    static final String KV_LAST = "sticker:collect:last";
    /** 上次做磁盘账的时间戳（没候选时用它把"盘账"节流到每 30 分钟一次）。 */
    static final String KV_DISK_LAST = "sticker:collect:disk:last";
    /** 上轮收集状态（JSON）。 */
    static final String KV_STATUS = "sticker:collect:status";

    /**
     * <b>候选行前缀 —— 跨技能接口键</b>：{@code sticker:cand/&lt;md5&gt;}。
     *
     * <p><b>生产者 = {@code 辅助识图}（看图手）</b>：它在"看图"时顺手写下候选行；<b>消费者 = 本类</b>。
     * 键：{@code sticker:cand/<md5>}，{@code md5 = Fs.md5(Utf8(url 非空 ? url : file))}
     * （＝本类 {@code srcMd5} 的同一配方）。值（JSON，键序固定）：
     * {@code {"v":1,"src":"…","url":"…","file":"…","faces":"无|一张|多张","text_len":N,"safe":true|false,
     * "desc":"…","id":"<msg_id>#<seg>","model":"…","ts":<ms>}}。</p>
     *
     * <p><b>清理口径（只有本技能做，避免两套规则并存／两边各删造成竞态）</b>：
     * {@code sticker:cand/} 是<b>跨技能接口键</b>，它的清理语义与既有那套前缀（{@code KV_SEEN} /
     * {@code KV_VISION} / {@code KV_STATUS}，都归本类管）<b>对齐成两条，且两条都删键</b>：</p>
     * <ol>
     *   <li><b>读到即删（消费即删）</b>：{@link #consumeCandidates} 每读到一行就无条件
     *       {@code kvDelete(key,false)}，防重复消费；</li>
     *   <li><b>过期即删</b>：行的 {@code ts}（<b>生产者写行的毫秒时间戳</b>，不是本技能的 tick 时间）
     *       早于 {@code now - CAND_TTL_MS} ⇒ 删键 + 一行日志（防"技能没装/心跳没跑"时孤儿候选积压）。</li>
     * </ol>
     * <p><b>写只有生产者写、删只有本类删</b> ⇒ 不会出现两边各自删的竞态。</p>
     */
    static final String KV_CAND = "sticker:cand/";

    /**
     * 候选行的<b>最长寿命（毫秒）＝ 24 小时</b>。
     *
     * <p>为什么是 24 小时：看图手写下的候选行里那份下载 URL 带 NapCat 的 {@code rkey} 签名，
     * 隔久了必然过期（旧口径的 {@code 队列TTL分钟} 默认只有 45 分钟，就是为这件事）；
     * 而"技能没装 / 心跳没跑"时消费者根本不删键 ⇒ 键会一直积压。
     * 取 24 小时是"跨得过一次停机/一天没心跳，又不会让一堆烂 URL 复活"的量级：
     * 比旧队列 TTL（45 分钟）宽得多（不必因为心跳间隔而误杀），又远短于"一周后还去下旧图"。
     * 乱丢都由本常量判定，<b>丢弃必删键、必留一行日志</b>。</p>
     */
    static final long CAND_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 一轮最多读多少行候选（防一次 tick 被几千个键拖住；超出的留在 kv 里下一轮再读，不删）。 */
    private static final int CAND_SCAN_MAX = 500;

    /** 算"这是表情包"的 {@code sub_type} 集（逐字照 V3 {@code QQMessage.isStickerImage()}：1/2/3/4/7）。 */
    static final String[] SUBS = {"1", "2", "3", "4", "7"};

    /** 图中文字上限：{@code >=} 这个数就不收（甲方：文字小于 15 字）。 */
    static final int TEXT_MAX = 15;
    /** 视觉审查能吃的最大字节（照 V3 {@code ImageDownloader.MAX_VISION_IMAGE_BYTES}）。 */
    private static final int VISION_MAX = 3 * 1024 * 1024;
    /** 语境截断长度（存进 {@code scene}；scene 是检索面）。 */
    private static final int SCENE_CHARS = 30;
    /** 视觉描述截断长度（优先当 scene 用，最可检索）。 */
    private static final int DESC_CHARS = 40;
    /** 一次读队列的上限（防一个坏文件把内存吃光）。 */
    private static final int QUEUE_READ_MAX = 5000;
    /** 单张下载超时。 */
    private static final int DL_TIMEOUT_MS = 20000;
    /** 没候选时，磁盘账最多多久查一次（毫秒）。 */
    private static final long DISK_IDLE_MS = 30L * 60L * 1000L;
    /** 一次批量查询的行上限（{@code Store.MAX_LIMIT}）。 */
    private static final int ROW_MAX = 10000;

    /** 违规词命中表（甲方口径 + V3 的经验「游戏」）：命中即不收。<b>长词在前</b>，便于点名到具体的那个词。 */
    static final String[] UNSAFE_WORDS = {"违规", "软色情", "色情", "成人", "敏感", "政治", "暴力", "游戏", "cos"};

    /** 视觉审查提示词：<b>一次调用拿五项</b>（数量 / 文字 / 描述 / 标签 / 安全判定）。 */
    static final String REVIEW_PROMPT =
            "你是图片内容审查员，请一次性完成五项任务：\n"
            + "1. 统计图片中的人脸/面部表情数量（真人、卡通、动物拟人等均可）。回答「无」「一张」或「多张」。\n"
            + "2. 只转写图中出现的文字（不要描述图片，只写图中文字；若无文字则写「无」）。\n"
            + "3. 客观描述图片内容（图片类型、主体、场景等，20 字以内）。\n"
            + "4. 给出 3~6 个检索标签（逗号分隔，用于以后按心情与语境挑图）。\n"
            + "5. 安全判定：是否含违规内容（软色情、Cos/Coser 图、政治敏感、色情、黄赌毒、暴力血腥、游戏截图）。\n\n"
            + "请严格按以下五行格式回答：\n"
            + "表情数量：<无 / 一张 / 多张>\n"
            + "图中文字：<图中文字，无则写「无」>\n"
            + "内容描述：<图片内容描述>\n"
            + "标签：<词1,词2,词3>\n"
            + "安全判定：<违规 或 正常>";

    // ================================================================ 入口 1：on_message（★ 已停用，恒 return）

    /**
     * <b>【已停用 · 恒 {@code return}】</b>原"入站同步预筛 + 登记候选队列"的入口。
     *
     * <p>甲方 2026-09-17 急令原话："表情包收集是跟着看图手走的，是在看图时的一个顺带，
     * 而不是主动自动去监听每一个消息" ⇒ 本技能<b>不再挂 {@code on_message}</b>
     * （{@code 表情包.md} 的 {@code hooks} 只剩 {@code on_timer}），也<b>不再对任何入站消息做判断</b>。
     * 收集现在<b>只</b>来自看图手写下的候选行（见 {@link #consumeCandidates}）。</p>
     *
     * <p><b>为什么是"删掉方法体"而不是"只把调用点摘掉"（工单给的两个做法，这里 (a)、(b) 一起做）</b>：
     * (a) 恒 {@code return} ⇒ <b>被调方</b>也断了 —— 将来谁再调一次，也什么都不会发生（不读事件、
     * 不写 kv、不写队列文件）；(b) {@code StickerSkill.on()} 里那条 {@code "on_message"} 分支<b>整条删除</b>
     * ⇒ <b>调用方</b>也断了。两条一起 = 工单要求的"双保险"，再加上 md 不声明它，三层都断，
     * <b>不留任何"能被误调用"的路</b>。签名保留（防别处引用编译不过；已核：仓内只有
     * {@code StickerSkill.java} 那一处调用点，已随 (b) 删除）。</p>
     *
     * <p><b>原口径留档</b>（现在归<b>生产者</b> = {@code 辅助识图}，不再由本技能做）：
     * ① 三道"她自己的图永不入库"的防线（{@code message_sent} / {@code extra.self} 标记 /
     * {@code user_id == self_id}）；② 群走 {@code 收集范围} + {@code 收集群白名单}、私聊走 {@code 收集私聊}；
     * ③ 三道便宜闸：{@code sub_type ∈ {1,2,3,4,7}} → {@code file_size} 体积预筛 → L1 已见闸（{@code KV_SEEN}）；
     * ④ 往 {@code <files>/sticker/queue.jsonl} 追加一行。以上在本类里<b>已全部停用</b>，且本类
     * <b>不存在任何"新写入队列"的路径</b>（{@code sweep} 只排空残量）。</p>
     *
     * @param payload 保留（签名不变）；<b>本方法不再读它</b>
     * @param h       保留（签名不变）；<b>本方法不再读它</b>
     * @param c       保留（签名不变）；<b>本方法不再读它</b>
     */
    static void capture(JsonObject payload, Host h, StickerSkill.Cfg c) {
        return;                                      // ★ 死路：恒 return（理由见上面 javadoc）
    }

    // ================================================================ 她自己的图 / 会话键

    /**
     * 事件（或<b>候选行</b>）里有没有"这是她自己发的"的标记 —— <b>消费侧那一道结构性检查</b>。
     *
     * <p>两道在哪一侧生效：① {@code message_sent} / ③ {@code user_id == self_id} 需要<b>入站事件</b>，
     * 现在由<b>生产者</b>（{@code 辅助识图} 的看图手）在写候选行之前判（原 {@code capture} 的前两道）；
     * ② 这一道（标记）<b>在消费侧仍然生效</b>：{@link #consumeCandidates} 对每一行候选都调它，
     * 命中就<b>跳过并删键 + 一行日志</b>（{@code cand_self}）。</p>
     *
     * <p>基板线的标记行口径是 {@code extra.self=true}（{@code notes\team-board.md} 接口公告区）。
     * 本方法认三种实际能拿到的形状，<b>多认一种不引入新规则</b>：</p>
     * <ol>
     *   <li>顶层 {@code _self}（钩子 payload 的保留前缀，基板自己用 {@code _handled}/{@code _caller}
     *       这一族；一起认，免得两边口径漂）；</li>
     *   <li>{@code extra.self} / {@code extra._self}；</li>
     *   <li>{@code _record.extra.self} / {@code _record.extra._self} —— 基板通知钩子把整行挂在
     *       {@code payload._record} 上的既有做法（{@code QqGateway.java:271-272}）。</li>
     * </ol>
     * <p>值一律走 {@link J} 的强类型读（{@code J.b}：认 {@code true/1/yes/on}，非布尔原语不炸）。</p>
     *
     * <p><b>候选行的 {@code self}</b>（接口里若有）由 {@link #selfFlagged} 单独读顶层那个键 ——
     * 字段以生产者为准，<b>没有就不猜</b>（既不因为"没有"就拒，也不因为"没有"就当合格）。</p>
     */
    static boolean selfMarked(JsonObject payload) {
        if (payload == null) return false;
        if (J.b(payload, "_self", false)) return true;
        if (markedSelf(payload)) return true;
        return markedSelf(J.sub(payload, "_record"));
    }

    /** {@code holder.extra.self} / {@code holder.extra._self}（{@link #selfMarked} 的一层）。 */
    private static boolean markedSelf(JsonObject holder) {
        if (holder == null) return false;
        JsonObject extra = J.sub(holder, "extra");
        if (extra == null) return false;
        return J.b(extra, "self", false) || J.b(extra, "_self", false);
    }

    /**
     * 这条候选属于哪个会话 —— <b>{@code Caller.session()} 的形状</b>（群 {@code group:<群号>}、
     * 私聊 {@code qq:<QQ>}）。
     *
     * <p><b>⚠ 本轮起没有调用者</b>：它唯一的调用点在已停用的 {@code capture} 里（那边的入站事件
     * 才带得上真实 caller）；候选行不带会话面（接口冻结）⇒ 走不到这里。方法保留原文
     * （不删任何既有逻辑/断言），一旦将来又有"带事件的行"要算会话键，口径仍在这里。</p>
     *
     * <p>取值优先 {@code h.caller().session()}：钩子路径的 {@code Host.caller()} 就是真实发送者
     * （{@code hot\Skills.java:863}），私聊的 caller 由网关按 {@code groupId=0} 造
     * （{@code QqGateway.callerOf}）⇒ 与事件本身必然一致。拿不到（caller 为 null / 是控制台）
     * 时才按事件兜底 —— 这一层是"不许依赖上游给对 caller"的防御，不是第二条判据。</p>
     *
     * <p><b>为什么不给候选行在这里现问 {@code h.caller()}</b>：{@code on_timer} 的主体是定时器
     * （{@code console}）⇒ 会把每一张都写成 {@code console}，比不写更坏（这条口径原来写在
     * {@code process} 里，本轮原样搬进 {@link #processWithVerdict}）。</p>
     *
     * @return 会话键；两条路都拿不到时返回空串（那时不写这个字段，而不是写一个假值）
     */
    static String sessionOf(Ev ev, Host h) {
        String s = "";
        try {
            Caller c = h == null ? null : h.caller();
            if (c != null) s = Str.trim(c.session());
        } catch (Throwable ignored) {
        }
        if (Str.has(s) && !"console".equals(s)) return s;
        if (ev != null) {
            if (ev.isGroup() && ev.groupId() > 0L) return "group:" + ev.groupId();
            if (ev.isPrivate() && ev.userId() > 0L) return "qq:" + ev.userId();
        }
        return "";
    }

    // ================================================================ 入口 2：on_timer（唯一钩子；批量，可做网络 I/O）

    /**
     * 一次心跳里的收集段 —— 甲方令之后的<b>唯一</b>收集入口。
     *
     * <p>顺序（与旧口径同一支，只换了"候选从哪来"）：</p>
     * <ol>
     *   <li><b>读候选行</b>：{@code kv} 的 {@code sticker:cand/} 前缀（一次 {@code kvList}，零网络）。
     *       <b>只读不删</b> —— 写位没过时一个字节都不该动（连生产者的键也不该删，删了就真丢了）；</li>
     *   <li><b>残量队列</b>：读旧的 {@code queue.jsonl}。这条路的生产者 <b>{@code capture} 已停用</b>
     *       ⇒ 这里只作<b>残量清理</b>（整批丢弃 + 一行日志：不下载、不判定、不入库）；</li>
     *   <li>总闸 / 写位检查（照旧：{@code 收集开关} 与 {@code 收集审查} 关着就只留一行日志、直接返回）；</li>
     *   <li><b>消费候选</b>（{@link #consumeCandidates}）：读到即删键 → 判定 → 下载 → 去重 → 落盘 → 入库；</li>
     *   <li>磁盘账 + 状态 kv（照旧）。</li>
     * </ol>
     *
     * <p><b>零视觉调用</b>：本方法、{@link #consumeCandidates}、{@link #processWithVerdict} 全链
     * <b>一次都不调</b> {@code h.ai()} —— 判定整条来自候选行（理由见类头）。</p>
     */
    static void sweep(Host h, StickerSkill.Cfg c) {
        if (h == null || c == null) return;
        Store store = h.store();
        if (store == null) return;
        File dir = dirOf(h);
        if (dir == null) return;
        File qf = new File(dir, QUEUE);

        Stats st = new Stats();
        st.ts = System.currentTimeMillis();
        st.review = c.review;

        // ---- 1) 候选行（只读不删；生产者 = 辅助识图 的看图手）----
        List<JsonObject> cands = candRows(store, st);

        // ---- 2) 残量队列（生产者已停用：读出来只为清掉它）----
        List<JsonObject> q = readQueue(qf, st);
        st.queueIn = q.size();

        // 便宜的快路径：没候选、没残量时盘也不会长，磁盘账节流到每 30 分钟查一次
        long diskLast = num(store.kv(KV_DISK_LAST, "0"), 0L);
        boolean diskDue = st.queueIn > 0 || !cands.isEmpty() || diskLast <= 0L || st.ts - diskLast > DISK_IDLE_MS;
        if (st.queueIn == 0 && cands.isEmpty() && !diskDue) return;
        if (!c.collectOn || !c.review) {                             // 关着的时候只留一行可观测记录
            st.last = !c.collectOn ? "collect_off" : "review_off";
            log(h, "自动收集未启用（" + st.last + "）："
                    + (c.collectOn ? "收集审查 关着 ⇒ 没有索引内容 ⇒ 不许入库（打开它才会收）"
                    : "收集开关 关着")
                    + "；候选键一行都没读也没删（等打开后按 ts 过期口径处理，见注释）");
            writeStatus(store, st, c, dir);
            if (diskDue) store.kvSet(KV_DISK_LAST, String.valueOf(st.ts));
            return;
        }

        // ---- 3) 残量队列：整批丢弃（★ 生产者已停用，只作残量清理；本类没有任何"新写入队列"的路径）----
        if (st.queueIn > 0) {
            st.queueDropped = st.queueIn;
            writeQueue(qf, new ArrayList<JsonObject>());              // 落成空 ⇒ 下一轮走便宜快路径
            st.last = "queue_dropped";
            log(h, "残量队列清理：" + st.queueIn + " 行直接丢弃（这条路的生产者 capture 已停用，只作残量清理："
                    + "不下载、不判定、不入库；队列文件已清空）");
        }
        st.queueLeft = 0;

        String denyFile = h.selfPath(dir.getAbsolutePath(), 'W');     // 钩子主体 SYSTEM ⇒ 正常放行
        String denyDb = h.selfDb(LIB, 'W');
        if (denyFile != null || denyDb != null) {
            st.last = denyFile != null ? "deny_path" : "deny_db";
            log(h, "自动收集跳过（缺写位）：" + (denyFile != null ? denyFile : denyDb));
            writeStatus(store, st, c, dir);
            return;                                                  // 候选键一行都没删，下一轮再来
        }

        // ---- 4) 消费候选（判定来自候选行，全程零视觉调用）----
        consumeCandidates(h, c, cands, store, dir, st);

        if (diskDue) {
            st.diskFreed = diskSweep(store, dir, c.diskMb, st);       // 磁盘账
            st.diskBytes = dirBytes(dir);
            store.kvSet(KV_DISK_LAST, String.valueOf(st.ts));
        } else {
            st.diskBytes = dirBytes(dir);
        }
        writeStatus(store, st, c, dir);
        store.kvSet(KV_LAST, String.valueOf(st.ts));
        log(h, "自动收集 " + J.json(status(st, c)));
    }

    // ================================================================ 入口 2 的主体：消费看图手的候选行

    /**
     * 把 {@code kv} 里的候选行读出来（一次 {@code kvList}，<b>只读不删</b>）。
     *
     * <p>为什么读与删分两步：写位（{@code h.selfPath/selfDb}）没过时<b>一个字节都不该动</b>
     * —— 连生产者的键也不该删（删了就真丢了）。所以这里只读，删在 {@link #consumeCandidates}
     * 里、且在写位检查<b>之后</b>。</p>
     *
     * <p>一次最多读 {@link #CAND_SCAN_MAX} 行（按 key 升序，即 md5 序）：超出的<b>留在 kv 里</b>
     * 下一轮再读（本轮不删它们 —— "读到即删"只针对真读到的行）。</p>
     */
    private static List<JsonObject> candRows(Store store, Stats st) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (store == null) return out;
        try {
            List<JsonObject> rows = store.kvList(KV_CAND, CAND_SCAN_MAX);
            if (rows != null) out.addAll(rows);
        } catch (Throwable t) {
            st.candBad++;
            st.last = "cand_list_error";
        }
        return out;
    }

    /**
     * <b>消费候选行</b>（本轮新增的唯一收集路径；生产者 = {@code 辅助识图} 的看图手）。
     *
     * <p><b>接口（经理已冻结，逐字照用）</b>：键 {@code sticker:cand/<md5>}，
     * {@code md5 = Fs.md5(Utf8(url 非空 ? url : file))}（＝ {@code srcMd5} 的<b>同一配方</b>）；
     * 值（JSON，键序固定）：{@code {"v":1,"src":"…","url":"…","file":"…","faces":"无|一张|多张",
     * "text_len":N,"safe":true|false,"desc":"…","id":"<msg_id>#<seg>","model":"…","ts":<ms>}}。</p>
     *
     * <p>逐行的处理次序（<b>先删键、再判定</b>）：</p>
     * <ol>
     *   <li><b>读到即无条件删键</b>（{@code kvDelete(k,false)}）—— 跨技能接口键的清理口径之一：
     *       <b>写只有生产者写、删只有本技能删</b>，不会出现两边各自删的竞态；</li>
     *   <li>值解析不出 JSON ⇒ 丢弃 + 日志 {@code cand_bad}；</li>
     *   <li>版本 {@code v != 1} ⇒ 丢弃 + 日志 {@code cand_bad_version}（接口冻结在 v=1，不认的版本不猜）；</li>
     *   <li><b>过期即删</b>：行的 {@code ts}（<b>生产者写行的毫秒时间戳</b>，不是本 tick 的时间）
     *       早于 {@code now - CAND_TTL_MS}（24 小时）⇒ 丢弃 + 日志 {@code cand_expired}；
     *       {@code ts <= 0}（缺/坏）⇒ 新鲜度判不了，同样丢弃 + 日志 {@code cand_bad_ts}（fail-closed）；</li>
     *   <li><b>她自己的图</b>：{@link #selfFlagged} 命中 ⇒ 跳过 + 日志 {@code cand_self}
     *       （这是消费侧那一道；入站那三道在生产者侧，见类头"哪一道在哪一侧生效"）；</li>
     *   <li><b>判定</b>：{@code safe==true} <b>且</b> {@code faces=="一张"} <b>且</b>
     *       {@code text_len < TEXT_MAX}（15）⇒ 可入库；否则丢弃 + 日志（原因码与 {@code applyVerdict}
     *       同一套词汇，加 {@code cand_} 前缀）；</li>
     *   <li>可入库的行按 <b>{@code ts ASC}</b>（最旧的先处理，与队列/池子老口径同一支）取前
     *       {@code 每轮上限}（{@code c.perTick}）条去 {@link #processWithVerdict}；本轮没轮到的
     *       ⇒ 计数 + 一行日志 {@code cand_defer}（键已按"读到即删"删掉，<b>不静默</b>）。</li>
     * </ol>
     *
     * @return 判定通过（可入库）的行数
     */
    private static int consumeCandidates(Host h, StickerSkill.Cfg c, List<JsonObject> rows,
                                        Store store, File dir, Stats st) {
        if (rows == null || rows.isEmpty()) return 0;
        int perTick = c.perTick;                                      // 已由 StickerSkill.load 归一（>= 1）
        List<JsonObject> ok = new ArrayList<JsonObject>();
        for (JsonObject kvRow : rows) {
            if (kvRow == null) continue;
            String key = Str.trim(J.s(kvRow, "k", ""));
            if (!Str.has(key) || !key.startsWith(KV_CAND)) continue;   // 结构性：只动本前缀的键
            String val = Str.nz(J.s(kvRow, "v", ""));
            st.candIn++;
            store.kvDelete(key, false);                                // ★ 读到即无条件删键（防重复消费）
            JsonObject cand = J.obj(val);
            if (cand == null || cand.size() == 0) {
                st.candBad++;
                st.last = "cand_bad";
                log(h, "候选行丢弃 cand_bad（值不是 JSON）key=" + key);
                continue;
            }
            if (J.l(cand, "v", 0L) != 1L) {                            // 接口版本：只认 v=1
                st.candBad++;
                st.last = "cand_bad_version";
                log(h, "候选行丢弃 cand_bad_version（v=" + J.s(cand, "v", "")
                        + "，接口只认 v=1）key=" + key);
                continue;
            }
            long ts = J.l(cand, "ts", 0L);
            if (ts <= 0L || st.ts - ts > CAND_TTL_MS) {                // ★ 过期即删（孤儿候选防积压）
                st.candExpired++;
                st.last = ts <= 0L ? "cand_bad_ts" : "cand_expired";
                log(h, "候选行丢弃 " + st.last + "（ts=" + ts + "，now=" + st.ts
                        + "，TTL=" + CAND_TTL_MS + "ms）key=" + key);
                continue;
            }
            if (selfFlagged(cand)) {                                   // ★ 防线②：她自己的图（消费侧这一道）
                st.candSelf++;
                st.last = "cand_self";
                log(h, "候选行丢弃 cand_self（带 self 标记 ⇒ 她自己的图不入库）key=" + key
                        + " id=" + J.s(cand, "id", ""));
                continue;
            }
            String bad = verdictReason(cand);
            if (Str.has(bad)) {                                        // ★ 判定：safe + 一张脸 + 文字 < 15
                st.candRejected++;
                st.last = bad;
                log(h, "候选行丢弃 " + bad + "（faces=" + J.s(cand, "faces", "")
                        + " text_len=" + J.i(cand, "text_len", 0)
                        + " safe=" + J.s(cand, "safe", "") + "）key=" + key);
                continue;
            }
            ok.add(cand);
        }
        if (ok.isEmpty()) return 0;

        sortByTsAsc(ok);                                              // ts ASC：最旧的先处理
        Set<String> round = new HashSet<String>();                    // L2：本轮内的内存幂等
        int done = 0;
        int defer = 0;
        for (int i = 0; i < ok.size(); i++) {
            JsonObject cand = ok.get(i);
            String srcKey = srcKeyOf(cand);                           // 有 URL 用 URL，否则用 file
            if (Str.has(srcKey) && !round.add(srcKey)) {
                st.dupQueue++;
                continue;
            }
            if (done >= perTick) {
                defer++;                                              // 本轮没轮到的（键已删，日志点名）
                continue;
            }
            done++;
            JsonObject e = rowOf(cand);
            Review rv = verdictOf(cand);
            String r = processWithVerdict(e, rv, h, c, store, dir, st);
            if (!"added".equals(r) && !"exists".equals(r)) {
                log(h, "跳过 " + r + " url=" + Str.cut(Str.oneLine(srcKey), 80));
            }
        }
        st.candDeferred = defer;
        if (defer > 0) {
            log(h, "候选行本轮未处理 cand_defer：" + defer + " 行（每轮上限 " + perTick
                    + "，键已随\"读到即删\"删掉；下一轮不会再见到它们）");
        }
        return ok.size();
    }

    /** 候选行里有没有"这是她自己发的"的标记（消费侧的防线②）。字段以生产者为准：<b>没有就不猜</b>。 */
    private static boolean selfFlagged(JsonObject cand) {
        if (cand == null) return false;
        if (J.b(cand, "self", false)) return true;                    // 接口里若有顶层 self，用它
        return selfMarked(cand);                                      // 否则走既有那三种形状（extra.self / _self / _record.extra.*）
    }

    /**
     * 甲方规则的消费侧落点（<b>与 {@code applyVerdict} 同一套词汇</b>，前面加 {@code cand_} 点名来源）：
     * {@code safe==true} <b>且</b> {@code faces=="一张"} <b>且</b> {@code text_len < TEXT_MAX(15)} ⇒ 可入库。
     *
     * @return 空串 = 可入库；否则是结构性原因码（写进状态 kv 的 {@code last} 与计数）
     */
    static String verdictReason(JsonObject cand) {
        String faces = Str.trim(J.s(cand, "faces", ""));
        int textLen = J.i(cand, "text_len", 0);
        if (!"一张".equals(faces)) {                                   // 接口枚举就是 无/一张/多张 ⇒ 精确比
            if (faces.indexOf("多张") >= 0) return "cand_multi_face";
            if (textLen > 0) return "cand_text_only";
            return "cand_no_face";
        }
        if (textLen >= TEXT_MAX) return "cand_text_too_long(" + textLen + ")";   // 甲方：文字 < 15 字
        if (!J.b(cand, "safe", false)) return "cand_unsafe";
        return "";
    }

    /**
     * 用候选行构造 {@code Review} —— <b>{@code index} 的形状与 {@code applyVerdict} 造的一模一样</b>
     * （{@code {faces,text_len,is_sticker,safe,desc,tags,verdict}}，一个字不变）。
     *
     * <p>{@code tags} / {@code verdict}：候选行给了就用它，<b>没给就是空串</b>（旧口径里"那一行没有"
     * 也是空串）—— <b>不编内容</b>。{@code is_sticker} / {@code safe} 写 {@code true}：能走到这里
     * 就说明候选行已经过了 {@link #verdictReason}（一张脸 + 文字 &lt; 15 + safe），与 {@code applyVerdict}
     * 里"三条都过了才写 true"同式；{@code usableOne} 也要求 {@code index.safe==true}。</p>
     */
    private static Review verdictOf(JsonObject cand) {
        String faces = Str.trim(J.s(cand, "faces", ""));
        int textLen = J.i(cand, "text_len", 0);
        String desc = Str.trim(J.s(cand, "desc", ""));
        Review r = new Review();
        JsonObject idx = new JsonObject();
        idx.addProperty("faces", faces);
        idx.addProperty("text_len", textLen);
        idx.addProperty("is_sticker", true);
        idx.addProperty("safe", true);
        idx.addProperty("desc", desc);
        idx.addProperty("tags", Str.trim(J.s(cand, "tags", "")));
        idx.addProperty("verdict", Str.trim(J.s(cand, "verdict", "")));
        r.index = idx;
        r.desc = desc;
        r.ok = true;
        r.reason = "";
        return r;
    }

    /**
     * 候选行 → {@link #processWithVerdict} 认的那条"行"（形状与旧队列行同一支：{@code url/file/ts/mid}）。
     *
     * <p><b>只搬候选行真有的东西</b>：{@code ctx}（消息上下文）、{@code u}（发送者）、{@code g}（群号）、
     * {@code sess}（会话键）、{@code sub}（子类型）这几样接口里没有 ⇒ <b>一个都不编</b>
     * （{@code extra.mid} 从 {@code id} 的 {@code <msg_id>#<seg>} 前半段取，取不出就是 0）。</p>
     */
    private static JsonObject rowOf(JsonObject cand) {
        JsonObject e = new JsonObject();
        String url = Str.trim(J.s(cand, "url", ""));
        String fid = Str.trim(J.s(cand, "file", ""));
        if (Str.has(url)) e.addProperty("url", url);
        if (Str.has(fid)) e.addProperty("file", fid);
        e.addProperty("ts", J.l(cand, "ts", 0L));
        long mid = msgIdOf(J.s(cand, "id", ""));
        if (mid > 0L) e.addProperty("mid", mid);
        return e;
    }

    /** 候选行 {@code id}（{@code <msg_id>#<seg>}）里那半段消息 id；取不出返回 0（不猜）。 */
    private static long msgIdOf(String id) {
        String s = Str.nz(id).trim();
        int i = s.indexOf('#');
        if (i >= 0) s = s.substring(0, i);
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 按 {@code ts ASC} 排（最旧的先处理）；ts 相同保持原序（Java 的 sort 是稳定的）。 */
    private static void sortByTsAsc(List<JsonObject> rows) {
        try {
            java.util.Collections.sort(rows, new java.util.Comparator<JsonObject>() {
                @Override
                public int compare(JsonObject a, JsonObject b) {
                    long x = J.l(a, "ts", 0L);
                    long y = J.l(b, "ts", 0L);
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 单条处理

    /**
     * <b>【已停用 · 恒拒】</b>旧的"自己去做视觉审查"的入库入口 —— 它曾经是
     * {@link #reviewSafe} 的<b>唯一</b>调用点。
     *
     * <p>甲方令之后，判定必须由调用方传入（来自看图手的候选行）⇒ 这个方法<b>不下载、不判定、不入库</b>，
     * 恒返回 {@code no_verdict}（结构性结果码，写进状态 kv 的 {@code last}）。保留签名只为
     * "别处引用不会编译不过"（已核：仓内唯一调用点在 {@code sweep} 里，已改成
     * {@link #processWithVerdict}）。<b>删掉这一行之后，{@link #reviewSafe} 零调用者、不可达</b>
     * ⇒ 整条心跳路径上一次视觉调用都没有。</p>
     */
    static String process(JsonObject e, Host h, StickerSkill.Cfg c, Store store, File dir, Stats st) {
        st.last = "no_verdict";
        return "no_verdict";                       // ★ 死路：没有外部判定就不许入库（这条路上不许有视觉调用）
    }

    /**
     * 处理一条候选（<b>判定由外部传入</b>）：下到临时件 → 复核（体积/魔数/内容哈希）→
     * L3 内容去重 → <b>用调用方给的判定</b>当硬闸门 → 写索引内容 → 改名落盘 → 入库。
     *
     * <p>与旧 {@link #process} 相比<b>只差一处</b>：{@code Review} 不再由 {@link #reviewSafe} 现算，
     * 而是<b>从候选行构造</b>（{@link #verdictOf}）；其余（{@code Http.download} → {@code magicOf} →
     * {@code Fs.md5} 内容哈希 → L3 去重 {@code store.list(LIB,{hash})} → 落盘 {@code move} →
     * 写 {@code sticker} 行）<b>一个字节都没动</b>。</p>
     *
     * @param e  一条"行"（候选行搬过来的：{@code url/file/ts/mid}）
     * @param rv 判定（{@code Review}，形状不变）
     * @return 一个结构性结果码（写进状态 kv 的 {@code last} 与计数）
     */
    static String processWithVerdict(JsonObject e, Review rv, Host h, StickerSkill.Cfg c,
                                     Store store, File dir, Stats st) {
        if (!c.review) {                                              // 没有索引内容 ⇒ 不入库（且不下载）
            st.reviewOff++;
            st.last = "review_off";
            return "review_off";
        }
        String rawUrl = Str.trim(J.s(e, "url", ""));
        String fid = Str.trim(J.s(e, "file", ""));
        String url = rawUrl;
        if (!Str.has(url)) url = Str.trim(h.inboundImageUrl(fid));     // 兜底（大概率已过期）
        if (!Str.has(url)) {
            st.noUrl++;
            st.last = "no_url";
            return "no_url";
        }
        String srcMd5 = md5(Str.has(rawUrl) ? rawUrl : fid);
        File part = new File(dir, "dl-" + short16(srcMd5) + PART);
        Http.Dl dl = Http.download(url, part, DL_TIMEOUT_MS);
        if (!dl.ok()) {
            delete(part);
            st.failed++;
            st.last = Str.has(dl.blocked) ? ("blocked_" + dl.blocked)
                    : (Str.has(dl.error) ? Str.cut(Str.oneLine(dl.error), 60) : "download_failed");
            return st.last;
        }
        if (c.maxMb > 0 && dl.size > (long) c.maxMb * 1048576L) {      // 体积复核（file_size 不可信）
            delete(part);
            st.oversize++;
            st.last = "oversize";
            return "oversize";
        }
        String magic = magicOf(part);
        if (!Str.has(magic)) {                                         // 魔数闸
            delete(part);
            st.notImage++;
            st.last = "not_image";
            return "not_image";
        }
        String md5 = Fs.md5(part);
        if (!Str.has(md5)) {
            delete(part);
            st.failed++;
            st.last = "hash_failed";
            return "hash_failed";
        }
        // L3 内容去重（构造性）：同一张图换 URL / 换群 = 只留一行；已入库的已有索引内容，不再花钱审查
        List<JsonObject> hit = store.list(LIB, J.obj("hash", md5), 1, null);
        if (hit != null && !hit.isEmpty()) {
            delete(part);
            mergeDup(store, hit.get(0), e, url, srcMd5, st);
            st.dup++;
            st.last = "exists";
            return "exists";
        }
        // ★ 入库硬闸门：判定<b>来自候选行</b>（调用方传入的 rv）—— 这里<b>不再调视觉</b>（reviewSafe 的
        //   唯一调用点已被本行取代）。拒了就删临时件、不落盘、不入库。
        if (!rv.ok) {
            delete(part);
            st.rejected++;
            st.last = rv.reason;
            return rv.reason;
        }
        // 语境：视觉描述 → 消息上下文 → 默认语境；全空则不收（scene 是检索面，空 scene 的图永远挑不出来）
        String scene = Str.has(rv.desc) ? Str.cut(Str.oneLine(rv.desc), DESC_CHARS)
                : Str.cut(Str.oneLine(J.s(e, "ctx", "")), SCENE_CHARS);
        if (!Str.has(scene)) scene = Str.trim(c.defScene);
        if (!Str.has(scene)) {
            delete(part);
            st.noScene++;
            st.last = "no_scene";
            return "no_scene";
        }
        String ext = extOf(url, magic);
        File dst = new File(dir, short16(md5) + ext);
        if (dst.isFile()) {
            delete(part);                                              // 同内容同名的成品已在盘上（表里没行）
        } else if (!move(part, dst)) {
            delete(part);
            st.failed++;
            st.last = "rename_failed";
            return "rename_failed";
        }
        JsonObject row = new JsonObject();
        row.addProperty("file", dst.getAbsolutePath());
        row.addProperty("hash", md5);
        row.addProperty("scene", scene);
        row.addProperty("source", SRC_AUTO);
        row.addProperty("uses", 0);
        JsonObject extra = new JsonObject();
        extra.addProperty("kind", "file");
        extra.addProperty("url", url);                                // ★ 本地件被磁盘账清掉后的唯一退路
        extra.addProperty("src_md5", srcMd5);
        extra.addProperty("added_by", J.l(e, "u", 0L));
        extra.addProperty("g", J.l(e, "g", 0L));
        // ★ 会话键（与队列行同值）：群 group:<群号> / 私聊 qq:<QQ>。
        //   **只读队列行里的 sess**，绝不在这里重新问 Host —— sweep 那一侧的 Host 是定时主体
        //   （session()="console"），在这里现问会把每一张图都写成 console。队列行缺 sess
        //   （老版本写的行 / 手工塞的行）时按 extra.g 口径兜底成 group:<群号>，群图口径统一。
        String sess = Str.trim(J.s(e, "sess", ""));
        if (!Str.has(sess)) {
            long g0 = J.l(e, "g", 0L);
            if (g0 > 0L) sess = "group:" + g0;
        }
        if (Str.has(sess)) extra.addProperty("sess", sess);
        extra.addProperty("mid", J.l(e, "mid", 0L));
        extra.addProperty("sub", J.s(e, "sub", ""));
        extra.addProperty("size", dl.size);
        extra.addProperty("ts", J.l(e, "ts", 0L));
        extra.add("index", rv.index);                                 // ★ 索引内容（发图侧按它匹配）
        row.add("extra", extra);
        long id = store.put(LIB, row);
        if (id <= 0L) {
            delete(dst);
            st.failed++;
            st.last = "insert_failed";
            return "insert_failed";
        }
        st.added++;
        st.last = "added";
        return "added";
    }

    /** L3 命中：只补 {@code extra}（行不动、不重复插、不再审查）。 */
    private static void mergeDup(Store store, JsonObject row, JsonObject e, String url, String srcMd5, Stats st) {
        long id = J.l(row, "id", 0L);
        if (id <= 0L) return;
        JsonObject extra = J.sub(row, "extra");
        if (extra == null) extra = new JsonObject();
        String first = Str.trim(J.s(extra, "url", ""));
        // ★ extra.url 保留**首个**：本地件就是从它落的；后来的 URL 可能已经过期（rkey 签名会烂）。
        if (!Str.has(first)) {
            extra.addProperty("url", url);
            extra.addProperty("src_md5", srcMd5);
        } else {
            extra.addProperty("src_md5", Str.trim(J.s(extra, "src_md5", srcMd5)));
            extra.addProperty("dup_urls", pushUrl(J.s(extra, "dup_urls", ""), url));
        }
        extra.addProperty("g", J.l(e, "g", 0L));
        // ★ 会话键与上面两行同一支：写"最近一次见到这张图的会话"（L3 命中不新增行 ⇒ 这不是"入库"口径）。
        String sess = Str.trim(J.s(e, "sess", ""));
        if (!Str.has(sess)) {
            long g0 = J.l(e, "g", 0L);
            if (g0 > 0L) sess = "group:" + g0;
        }
        if (Str.has(sess)) extra.addProperty("sess", sess);
        extra.addProperty("mid", J.l(e, "mid", 0L));
        extra.addProperty("seen_ts", st.ts);
        extra.addProperty("dup_hits", J.i(extra, "dup_hits", 0) + 1);
        JsonObject patch = new JsonObject();
        patch.addProperty("id", id);
        patch.add("extra", extra);
        store.put(LIB, patch);
    }

    // ================================================================ 视觉审查（★ 全文保留，但已无调用者）

    /** 审查结论：{@code ok=false} 表示这张不收；{@code reason} 是结构性原因码；{@code index} 是索引内容。 */
    static final class Review {
        boolean ok = false;
        String reason = "";
        String desc = "";
        JsonObject index = new JsonObject();
    }

    /**
     * 入库前的视觉审查（甲方规则）：<b>有且仅有一张脸</b> + <b>图中文字 &lt; 15 字</b> + <b>无违规</b>。
     *
     * <p>一次调用拿五项，结果按内容 MD5 缓存（同一张图只花一次钱）。
     * <b>模型不可用一律不收也不崩</b>（{@code review_unavailable} / {@code review_error}），
     * 既不留静默空档，也不把"打不通"当成"合格"。</p>
     *
     * <p><b>⚠ 本轮起零调用者（不可达）</b>：它旧的唯一调用点在 {@code process} 里，而 {@code process}
     * 已停用（恒拒）；活着的落库路径是 {@link #processWithVerdict}，判定由候选行传入。
     * <b>代码与口径逐字保留、一个字都没删</b>（不削弱任何既有断言），但它<b>不在任何活路径上</b>
     * ⇒ 心跳路径上的视觉调用数为 <b>0</b>。本类里 {@code h.ai()} / {@code ai.vision(...)} 也只出现在
     * 本方法内（行号见 REPORT 的 file:line 证据）。</p>
     */
    static Review reviewSafe(Host h, File f, String md5, Store store, StickerSkill.Cfg c) {
        Review r = new Review();
        JsonObject cached = store == null ? null : J.obj(store.kv(KV_VISION + md5, ""));
        if (cached != null) {                                          // 缓存命中：不再调模型
            applyVerdict(r, J.s(cached, "verdict", ""));
            return r;
        }
        try {
            long size = Fs.size(f);
            if (size <= 0L || size > VISION_MAX) {                      // 送不进视觉：不收（照 V3 的 3MB 口径）
                r.reason = "vision_oversize";
                return r;
            }
            DeepSeek ai = h.ai();
            if (ai == null) {
                r.reason = "review_unavailable";
                log(h, "审查不可用（ai=null）⇒ 不收这张图（不是静默丢，也不是当合格）");
                return r;
            }
            byte[] bytes = Fs.readBytes(f);
            if (bytes == null || bytes.length == 0) {
                r.reason = "review_unavailable";
                return r;
            }
            String dataUrl = "data:" + mime(magicOf(f)) + ";base64," + Base64.getEncoder().encodeToString(bytes);
            List<String> urls = new ArrayList<String>();
            urls.add(dataUrl);
            JsonArray messages = new JsonArray();
            messages.add(Msg.userWithImages(REVIEW_PROMPT, urls));       // 一次调用拿五项（V3 的经验 P9）
            Res res = ai.vision(messages, null);
            String text = res == null ? "" : Str.nz(res.content);
            if (res == null || !res.ok() || Str.blank(text)) {
                r.reason = "review_unavailable";
                log(h, "审查不可用（模型没给出结论）⇒ 不收这张图："
                        + Str.cut(Str.oneLine(res == null ? "(null)" : Str.nz(res.error)), 120));
                return r;
            }
            String verdict = Str.trim(text);
            applyVerdict(r, verdict);
            if (store != null) {
                store.kvSet(KV_VISION + md5, J.json(J.obj("ts", System.currentTimeMillis(),
                        "ok", r.ok, "reason", r.reason, "desc", r.desc, "verdict", verdict)));
            }
        } catch (Throwable t) {
            // ★ ai() 抛异常时绝不把异常当"合格"：不收、不崩，留一行可观测记录
            r.ok = false;
            r.reason = "review_error";
            log(h, "审查异常 ⇒ 不收这张图：" + Str.cut(String.valueOf(t), 120));
        }
        return r;
    }

    /**
     * 甲方规则的落点（三条硬规则），并把结构化结果做成<b>索引内容</b>。
     *
     * <p>不通过时 {@code ok=false} 且点明原因码（便于回头看看为什么没收）。</p>
     */
    private static void applyVerdict(Review r, String verdict) {
        r.index = new JsonObject();
        if (Str.blank(verdict)) {
            r.reason = "review_unavailable";
            return;
        }
        String faces = field(verdict, "表情数量");
        String text = field(verdict, "图中文字");
        String desc = field(verdict, "内容描述");
        String tags = field(verdict, "标签");
        String safe = field(verdict, "安全判定");
        int textLen = ("无".equals(Str.trim(text)) || !Str.has(text)) ? 0 : countChars(text);

        JsonObject idx = new JsonObject();
        idx.addProperty("faces", faces);
        idx.addProperty("text_len", textLen);
        idx.addProperty("is_sticker", false);
        idx.addProperty("safe", false);
        idx.addProperty("desc", desc);
        idx.addProperty("tags", tags);
        idx.addProperty("verdict", verdict);
        r.index = idx;
        r.desc = desc;

        boolean oneFace = faces.indexOf("一张") >= 0;
        idx.addProperty("is_sticker", oneFace && textLen < TEXT_MAX);
        if (!oneFace) {
            if (faces.indexOf("多张") >= 0) {
                r.reason = "multi_face";
            } else if (textLen > 0) {
                r.reason = "text_only";                                 // 纯文字图：没有脸、只有字
            } else {
                r.reason = "no_face";
            }
            return;
        }
        if (textLen >= TEXT_MAX) {                                       // 甲方：文字小于 15 字才收
            r.reason = "text_too_long(" + textLen + ")";
            return;
        }
        String low = Str.lower(safe);
        if (Str.has(low)) {
            for (String w : UNSAFE_WORDS) {
                if (low.indexOf(Str.lower(w)) >= 0) {
                    r.reason = "unsafe(" + w + ")";
                    return;
                }
            }
            if ("正常".equals(Str.trim(safe))) {
                idx.addProperty("safe", true);
            } else {
                r.reason = "unsafe_unknown";                             // 判定词不认 ⇒ 保守不收
                return;
            }
        } else {
            r.reason = "unsafe_unknown";
            return;
        }
        r.ok = true;                                                     // 三条都过
        r.reason = "";
    }

    /** 取五行格式里某一行 {@code 键：值} 的值（中英文冒号都认）。 */
    private static String field(String text, String key) {
        if (Str.blank(text) || Str.blank(key)) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = Str.trim(raw).replace("*", "").replace("`", "");
            int i = line.indexOf('：');
            if (i < 0) i = line.indexOf(':');
            if (i <= 0) continue;
            if (key.equals(Str.trim(line.substring(0, i)))) return Str.trim(line.substring(i + 1));
        }
        return "";
    }

    /** 图中文字计数：去掉空白与常见标点后数字符（照 V3 {@code countChars} 的口径）。 */
    static int countChars(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if (ch < 128 && !Character.isLetterOrDigit(ch)) continue;     // 半角标点
            if ("，。！？、；：「」『』（）【】《》…—～·".indexOf(ch) >= 0) continue;
            n++;
        }
        return n;
    }

    // ================================================================ 发图侧：心情 → 索引内容 → 随机

    /**
     * 当前<b>心情与现状</b>的检索词（拿不到就返回空表）。
     *
     * <p>数据来源只有一个且<b>可读</b>：{@code 情绪} 技能写的 {@code emotion} 表（{@code scope}/{@code scope_id}
     * 定位到"这个人在这个场合"那一行）。它是跨技能唯一能被读到的情绪面 —— 情绪技能的"注入行"是给模型看的
     * 上下文，工具/钩子里读不到；这条限制写在方案的"降级"里。</p>
     *
     * <p>取词优先级：{@code last_emotion}（对方此刻的情绪标签，来自消息）→ 由
     * {@code angry}/{@code sad}/{@code affinity} 派生的粗档心情词 → {@code romance} 的关系词。
     * 全是<b>字面词</b>，不做模型判断。</p>
     */
    static List<String> moodTokens(Host h) {
        List<String> out = new ArrayList<String>();
        try {
            Store store = h == null ? null : h.store();
            if (store == null) return out;
            long qq = h.caller() == null ? 0L : h.caller().qq();
            long gid = h.caller() == null ? 0L : h.caller().groupId();
            if (qq <= 0L) return out;                                     // 控制台/拿不到会话：没有情绪面
            String scope = gid > 0L ? "group" : "private";
            String sid = gid > 0L ? (gid + ":" + qq) : String.valueOf(qq);
            JsonObject f = new JsonObject();
            f.addProperty("scope", scope);
            f.addProperty("scope_id", sid);
            List<JsonObject> rows = store.list(LIB_EMOTION, f, 1, "id DESC");
            if (rows == null || rows.isEmpty()) {
                log(h, "情绪事实读不到（emotion 表里没有这一行）⇒ 发图降级为随机");
                return out;
            }
            JsonObject row = rows.get(0);
            add(out, Str.trim(J.s(row, "last_emotion", "")));              // 对方此刻的情绪标签
            double angry = J.d(row, "angry", 0.0D);
            double sad = J.d(row, "sad", 0.0D);
            int aff = J.i(row, "affinity", 0);
            if (angry >= 60.0D) add(out, "生气");
            if (sad >= 60.0D) add(out, "难过");
            if (aff >= 10) add(out, "亲近");
            String rom = Str.lower(J.s(row, "romance", ""));
            if ("lover".equals(rom)) add(out, "亲密");
            if (out.isEmpty()) log(h, "情绪事实是「平静/陌生人」档 ⇒ 没有可用的检索词，发图走随机");
            return out;
        } catch (Throwable t) {
            return out;
        }
    }

    private static void add(List<String> l, String s) {
        String v = Str.trim(s);
        if (!Str.has(v)) return;
        if ("平静".equals(v) || "none".equalsIgnoreCase(v)) return;        // 平静/无关系 = 没有倾向
        if (!l.contains(v)) l.add(v);
    }

    /**
     * 按心情检索词挑一张：{@code scene} 精确 → 基板检索（LIKE）→ {@code extra} 片段（索引内容里的标签）
     * → <b>都没有就随机</b>（照 V3 的发图机制）。
     *
     * <p>命中的那一步与"退化为随机"都会记一行日志（可观测：一条挑图到底走了哪一步）。</p>
     *
     * @return 挑不到任何可用的行时返回 {@code null}
     */
    static JsonObject pickByMood(Host h, Store store, StickerSkill.Cfg c, List<String> tokens) {
        if (store == null) return null;
        if (tokens != null) {
            for (String t : tokens) {
                if (!Str.has(t)) continue;
                String how = "scene 精确";
                List<JsonObject> hit = usable(store.list(LIB, J.obj("scene", t), c.pool, "uses DESC, ts DESC"));
                if (hit.isEmpty()) {
                    how = "基板检索(LIKE)";
                    hit = usable(store.search(LIB, t, c.pool));
                }
                if (hit.isEmpty()) {
                    how = "extra 片段(索引标签)";
                    hit = usable(store.list(LIB, J.obj("_like", J.obj("extra", t)), c.pool, "uses DESC, ts DESC"));
                }
                if (!hit.isEmpty()) {
                    JsonObject r = hit.get(0);
                    log(h, "按心情命中索引内容：" + how + " 「" + t + "」 → #" + J.l(r, "id", 0L)
                            + " [" + J.s(r, "scene", "") + "]");
                    return r;
                }
            }
        }
        JsonObject r = random(store, c);                                   // 匹配不到 ⇒ 随机
        log(h, "心情检索词都没匹配到 ⇒ 随机一张" + (r == null ? "（库里没有可用的图）"
                : "：" + "#" + J.l(r, "id", 0L) + " [" + J.s(r, "scene", "") + "]"));
        return r;
    }

    /** 随机一张（本地文件在、或者有原链可回退的才算）。 */
    static JsonObject random(Store store, StickerSkill.Cfg c) {
        if (store == null) return null;
        List<JsonObject> pool = usable(store.list(LIB, null, Math.max(c.pool, 50), "uses DESC, ts DESC"));
        if (pool.isEmpty()) return null;
        int i = (int) (Math.abs(System.nanoTime()) % pool.size());
        return pool.get(i);
    }

    /**
     * 只留"真能发出去"的行 —— <b>与计数侧 {@link #usableOne} 同一份判据</b>：
     * {@code file} 非空；URL/fileid 形态的算可用（基板会把链接交给 NapCat）；本地路径形态的必须在盘上；
     * 带 {@code extra.index}（自动收的）必须 {@code safe=true}。
     *
     * <p>这里是**唯一**的挑选判据：{@link #pickByMood} / {@link #random} 与
     * {@code StickerSkill.usable} 都走 {@link #usableOne}。老写法是两份手抄的近似判据
     * （这一份查了盘上、没查 {@code safe}；{@code StickerSkill.usable} 连盘上都没查）
     * ⇒ "可用"在挑选侧与计数侧不是同一件事。</p>
     */
    static List<JsonObject> usable(List<JsonObject> rows) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (rows == null) return out;
        for (JsonObject r : rows) {
            if (r != null && usableOne(r)) out.add(r);
        }
        return out;
    }

    // ================================================================ 淘汰：年龄账 + 最低保有量

    /**
     * 可用行数（年龄账的底）：<b>可用 = send() 真能挑出去的那一行</b>——
     * {@code file} 非空；本地路径形态的文件必须在盘上；URL/fileid 形态的算可用（基板会把链接交给 NapCat）；
     * 带 {@code extra.index}（自动收的）必须 {@code safe=true}。
     */
    static int countUsable(Store store) {
        if (store == null) return 0;
        int n = 0;
        List<JsonObject> rows = store.list(LIB, null, ROW_MAX, null);
        if (rows == null) return 0;
        for (JsonObject r : rows) {
            if (r == null) continue;
            if (usableOne(r)) n++;
        }
        return n;
    }

    /**
     * <b>「可用」的唯一判据</b>（包内可见：{@link StickerSkill#usable} 与 {@link #usable} 都只用它）。
     *
     * <p>{@code file} 非空；URL/fileid 形态的算可用（基板会把链接交给 NapCat）；
     * 本地路径形态的<b>必须在盘上</b>；带 {@code extra.index}（自动收的）必须 {@code safe=true}。</p>
     */
    static boolean usableOne(JsonObject r) {
        String f = Str.trim(J.s(r, "file", ""));
        if (!Str.has(f)) return false;
        JsonObject extra = J.sub(r, "extra");
        JsonObject idx = extra == null ? null : J.sub(extra, "index");
        if (idx != null && !J.b(idx, "safe", false)) return false;          // 自动收的没判安全 ⇒ 不算可用
        String low = Str.lower(f);
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("base64://")) return true;
        try {
            return new File(f).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 年龄账（甲方：一张图超时 15 天淘汰，但库里可用数不得少于 {@code 最低保有量}）。
     *
     * <p>构造性口径：先算"全库可用数"，本轮最多只能删 {@code 可用数 - 最低保有量} 张
     * （不足就是 0 张 ⇒ <b>保住最老的也不删</b>），再从<b>超龄</b>的行里按 {@code ts ASC} 取前 N 张删。</p>
     *
     * @return 真删掉的行数
     */
    static int dropAged(Store store, long cutoff, int minKeep) {
        if (store == null) return 0;
        long usableTotal = countUsable(store);
        int allowed = (int) Math.max(0L, usableTotal - Math.max(0, minKeep));
        if (allowed <= 0) return 0;
        List<JsonObject> rows = store.list(LIB, J.obj("_lte", J.obj("ts", cutoff)), allowed, "ts ASC");
        int n = 0;
        if (rows == null) return 0;
        for (JsonObject r : rows) {
            long id = J.l(r, "id", 0L);
            if (id > 0L && store.delete(LIB, id)) n++;
        }
        return n;
    }

    // ================================================================ 磁盘账

    /**
     * 磁盘账：超 {@code 磁盘上限MB} 就删文件直到不超过。
     *
     * <p>淘汰顺序（三级，逐级兜底，保证"永不超上限"）：</p>
     * <ol>
     *   <li><b>孤儿文件</b>（表里没有任何一行指向它）；</li>
     *   <li>对应行的 {@code ts ASC}（<b>最旧的先走</b>；与行数账同一个纯滚动口径 ——
     *       {@code uses} 不参与淘汰顺序）—— <b>只删文件，行保留</b>，
     *       该行的 {@code file} 退化为 {@code extra.url}，发送侧会自动回退；</li>
     *   <li>还不够就按文件时间从旧到新兜底。</li>
     * </ol>
     *
     * @return 释放的字节数
     */
    static long diskSweep(Store store, File dir, int capMb, Stats st) {
        if (store == null || dir == null || capMb <= 0) return 0L;
        long cap = (long) capMb * 1048576L;
        long total = dirBytes(dir);
        if (total <= cap) return 0L;
        long freed = 0L;

        List<JsonObject> rows = store.list(LIB, null, ROW_MAX, "ts ASC");
        Set<String> owned = new HashSet<String>();
        if (rows != null) {
            for (JsonObject r : rows) {
                String p = Str.trim(J.s(r, "file", ""));
                if (Str.has(p)) owned.add(abs(p));
            }
        }
        for (File f : filesOf(dir)) {                                    // ① 孤儿优先
            if (total <= cap) break;
            if (owned.contains(abs(f.getAbsolutePath()))) continue;
            long n = Fs.size(f);
            if (Fs.deleteRec(f)) {
                total -= n;
                freed += n;
                st.diskOrphan++;
            }
        }
        if (total > cap && rows != null) {                               // ② 按行序
            for (JsonObject r : rows) {
                if (total <= cap) break;
                String p = Str.trim(J.s(r, "file", ""));
                if (!Str.has(p)) continue;
                File f = new File(p);
                if (!f.isFile() || !inside(dir, f)) continue;
                long n = Fs.size(f);
                if (Fs.deleteRec(f)) {
                    total -= n;
                    freed += n;
                    st.diskRow++;
                }
            }
        }
        if (total > cap) {                                               // ③ 兜底：按文件时间
            List<File> left = filesOf(dir);
            sortByTime(left);
            for (File f : left) {
                if (total <= cap) break;
                long n = Fs.size(f);
                if (Fs.deleteRec(f)) {
                    total -= n;
                    freed += n;
                    st.diskOld++;
                }
            }
        }
        st.diskBytes = total;
        return freed;
    }

    // ================================================================ 队列读写

    private static List<JsonObject> readQueue(File qf, Stats st) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (qf == null || !qf.isFile()) return out;
        String text = Fs.read(qf, "");
        if (Str.blank(text)) return out;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = Str.trim(raw);
            if (line.isEmpty()) continue;
            if (out.size() >= QUEUE_READ_MAX) {
                st.badLines++;
                continue;
            }
            JsonObject o = J.obj(line);
            if (o == null || o.size() == 0) {
                st.badLines++;
                continue;
            }
            out.add(o);
        }
        return out;
    }

    private static void writeQueue(File qf, List<JsonObject> rows) {
        if (qf == null) return;
        StringBuilder sb = new StringBuilder();
        if (rows != null) {
            for (JsonObject r : rows) {
                if (r == null) continue;
                sb.append(J.json(r)).append('\n');
            }
        }
        if (sb.length() == 0) {
            if (qf.isFile()) Fs.deleteRec(qf);
            return;
        }
        Fs.write(qf, sb.toString());
    }

    private static void trimQueue(File qf, int max) {
        if (qf == null || max <= 0) return;
        Stats dummy = new Stats();
        List<JsonObject> rows = readQueue(qf, dummy);
        if (rows.size() <= max) return;
        writeQueue(qf, new ArrayList<JsonObject>(rows.subList(rows.size() - max, rows.size())));
    }

    // ================================================================ 状态 / 小工具

    /** 一轮 sweep 的计数（写进 kv 状态，也是主人观察灰度的唯一入口）。 */
    static final class Stats {
        long ts = 0L;
        boolean review = false;
        /** 残量队列（旧 queue.jsonl）：读到的行数 / 本轮丢弃的行数（生产者已停用 ⇒ 只作残量清理）。 */
        int queueIn = 0;
        int queueLeft = 0;
        int queueDropped = 0;
        /** 候选行（看图手写的）：读到多少 / 判定通过但本轮没轮到多少 / 各类丢弃多少。 */
        int candIn = 0;
        int candDeferred = 0;
        int candExpired = 0;
        int candSelf = 0;
        int candRejected = 0;
        int candBad = 0;
        /** 旧队列那一套：随 capture 停用（{@code expired}/{@code trimmed} 不再增长，字段留着不改状态面）。 */
        int expired = 0;
        int trimmed = 0;
        int dupQueue = 0;
        int added = 0;
        int dup = 0;
        int failed = 0;
        int oversize = 0;
        int notImage = 0;
        int noScene = 0;
        int rejected = 0;
        int reviewOff = 0;
        int noUrl = 0;
        int badLines = 0;
        int diskOrphan = 0;
        int diskRow = 0;
        int diskOld = 0;
        long diskBytes = 0L;
        long diskFreed = 0L;
        String last = "";
    }

    private static JsonObject status(Stats st, StickerSkill.Cfg c) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", st.ts);
        o.addProperty("scope", c.scope);
        // ★ 私聊那一档（可观测：灰度期看一眼就知道"私聊到底收不收、关在哪一档"）。
        //   collect_private 是布尔结论，private_note 是它的来路：
        //   on / off / missing（prompt.md 里没这一行）/ invalid（值不认）/ prompt_unreadable（整份读不到）。
        o.addProperty("collect_private", c.collectPrivate);
        o.addProperty("private_note", c.privateNote);
        o.addProperty("review", st.review);
        o.addProperty("queue_in", st.queueIn);
        o.addProperty("queue_left", st.queueLeft);
        o.addProperty("queue_dropped", st.queueDropped);
        // ★ 候选行那一档（本轮新增，可观测）：读了多少、本轮没轮到多少、各类丢了多少。
        o.addProperty("cand_in", st.candIn);
        o.addProperty("cand_deferred", st.candDeferred);
        o.addProperty("cand_expired", st.candExpired);
        o.addProperty("cand_self", st.candSelf);
        o.addProperty("cand_rejected", st.candRejected);
        o.addProperty("cand_bad", st.candBad);
        o.addProperty("expired", st.expired);
        o.addProperty("trimmed", st.trimmed);
        o.addProperty("dup_queue", st.dupQueue);
        o.addProperty("added", st.added);
        o.addProperty("dup", st.dup);
        o.addProperty("failed", st.failed);
        o.addProperty("oversize", st.oversize);
        o.addProperty("not_image", st.notImage);
        o.addProperty("no_scene", st.noScene);
        o.addProperty("rejected", st.rejected);
        o.addProperty("review_off", st.reviewOff);
        o.addProperty("no_url", st.noUrl);
        o.addProperty("bad_lines", st.badLines);
        o.addProperty("disk_bytes", st.diskBytes);
        o.addProperty("disk_cap", (long) c.diskMb * 1048576L);
        o.addProperty("disk_freed", st.diskFreed);
        o.addProperty("disk_orphan", st.diskOrphan);
        o.addProperty("disk_row", st.diskRow);
        o.addProperty("disk_old", st.diskOld);
        o.addProperty("last", st.last);
        return o;
    }

    private static void writeStatus(Store store, Stats st, StickerSkill.Cfg c, File dir) {
        if (store == null) return;
        try {
            store.kvSet(KV_STATUS, J.json(status(st, c)));
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 外链中转（绝不把盘符路径交给 NapCat）

    /**
     * <b>外链中转是否就绪</b>（基板唯一那一套：{@code Relay.running()}）。
     *
     * <p>为什么技能要问这一句：NapCat 可能在<b>另一台机器</b>上，盘符路径（{@code C:\…}）它读不到。
     * 基板本来就在 {@code Api.rewriteLocalFile}（{@code src\sair\v4\qq\Api.java:505-524}）里做
     * "本地路径 → 外链 URL"：<b>中转在跑</b>时换成
     * {@code http://<relayPublicHost>:<port>/<token>/<绝对路径>}；
     * <b>中转没跑</b>时它只<b>记一行告警并原样透传</b>（保留同机口径）
     * ⇒ 那一刻交给 NapCat 的就是裸的 {@code C:\…}。</p>
     *
     * <p>所以技能侧必须先问一句：<b>能转就交本地件（基板会转），不能转就改用 {@code extra.url}，
     * 两者都不行就跳过</b> —— 任何情况下都不把盘符路径交出去。
     * 问不到（{@code napcat()==null} / 抛异常）按"没就绪"处理（fail-closed）。</p>
     */
    static boolean relayReady(Host h) {
        try {
            Api api = h == null ? null : h.napcat();
            Relay r = api == null ? null : api.relay();
            return r != null && r.running();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这个值是不是"本地路径形态"（盘符 / {@code file:} / 绝对路径）—— 跨机 NapCat 读不到的那类。 */
    static boolean isLocalPath(String v) {
        String t = Str.trim(v);
        if (t.isEmpty()) return false;
        String low = Str.lower(t);
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("base64://")) return false;
        if (low.startsWith("file:")) return true;
        if (t.length() >= 2 && t.charAt(1) == ':') return true;           // C:\x / C:/x
        return t.charAt(0) == '/' && !t.startsWith("//");
    }

    /** 图片目录 {@code <dataDir>/files/sticker}（不存在就建）。 */
    static File dirOf(Host h) {
        try {
            File base = h == null || h.conf() == null ? null : h.conf().filesDir();
            if (base == null) return null;
            File d = new File(base, DIR);
            if (!d.isDirectory() && !Fs.mkdirs(d)) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目录里所有普通文件（不含队列文件本身）。 */
    static List<File> filesOf(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            if (QUEUE.equals(f.getName())) continue;
            out.add(f);
        }
        return out;
    }

    /** 目录占用字节（图片文件 + 临时件；队列文件不算）。 */
    static long dirBytes(File dir) {
        long n = 0L;
        for (File f : filesOf(dir)) n += Fs.size(f);
        return n;
    }

    private static void sortByTime(List<File> fs) {
        try {
            java.util.Collections.sort(fs, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long x = a.lastModified();
                    long y = b.lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 该条候选用哪个值做 L1/L2 的键（有 URL 用 URL，否则 file 值）。 */
    static String srcKeyOf(JsonObject e) {
        if (e == null) return "";
        String url = Str.trim(J.s(e, "url", ""));
        return Str.has(url) ? url : Str.trim(J.s(e, "file", ""));
    }

    /** 是不是表情包子类型（1 表情 / 2 热图 / 3 斗图 / 4 智图 / 7 贴图）。 */
    static boolean isStickerSub(String sub) {
        String s = Str.trim(sub);
        for (String x : SUBS) if (x.equals(s)) return true;
        return false;
    }

    /** 内容哈希做文件名：前 16 位（文件名只用于本地落盘，库存规模下碰撞可忽略）。 */
    static String short16(String md5) {
        String h = Str.nz(md5);
        return h.length() > 16 ? h.substring(0, 16) : h;
    }

    private static String md5(String s) {
        try {
            return Fs.md5(Str.nz(s).getBytes(Fs.UTF8));
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code dup_urls} 的追加口径：新的在前，最多留 3 条。 */
    private static String pushUrl(String old, String url) {
        String u = Str.trim(url);
        if (!Str.has(u)) return Str.nz(old);
        List<String> all = new ArrayList<String>();
        all.add(u);
        for (String x : Str.nz(old).split("\\s*\\|\\s*", -1)) {
            String t = Str.trim(x);
            if (!t.isEmpty() && !t.equals(u) && all.size() < 3) all.add(t);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    /** 魔数识别：返回 {@code png/jpg/gif/webp/bmp}，不认返回空串（顺带修掉 V3 "一律改 .png" 的坑）。 */
    static String magicOf(File f) {
        try {
            byte[] b = readHead(f, 16);
            if (b == null || b.length < 4) return "";
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
            if (b[0] == 'B' && b[1] == 'M') return "bmp";
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String mime(String magic) {
        if ("png".equals(magic)) return "image/png";
        if ("jpg".equals(magic)) return "image/jpeg";
        if ("gif".equals(magic)) return "image/gif";
        if ("webp".equals(magic)) return "image/webp";
        if ("bmp".equals(magic)) return "image/bmp";
        return "application/octet-stream";
    }

    /** 后缀：魔数优先，其次 URL 路径扩展名，最后 {@code .jpg}。 */
    static String extOf(String url, String magic) {
        if ("png".equals(magic)) return ".png";
        if ("jpg".equals(magic)) return ".jpg";
        if ("gif".equals(magic)) return ".gif";
        if ("webp".equals(magic)) return ".webp";
        if ("bmp".equals(magic)) return ".bmp";
        String u = Str.nz(url);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        int dot = u.lastIndexOf('.');
        if (dot > 0 && dot < u.length() - 1) {
            String e = Str.lower(u.substring(dot + 1));
            if (e.length() <= 4) {
                if ("jpeg".equals(e)) return ".jpg";
                if ("png".equals(e) || "jpg".equals(e) || "gif".equals(e) || "bmp".equals(e) || "webp".equals(e)) {
                    return "." + e;
                }
            }
        }
        return ".jpg";
    }

    private static byte[] readHead(File f, int n) {
        java.io.InputStream in = null;
        try {
            if (f == null || !f.isFile()) return null;
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got == n) return buf;
            byte[] out = new byte[got];
            System.arraycopy(buf, 0, out, 0, got);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean move(File src, File dst) {
        try {
            if (src == null || dst == null || !src.isFile()) return false;
            if (src.renameTo(dst)) return true;
            if (Fs.copy(src, dst)) {
                Fs.deleteRec(src);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void delete(File f) {
        try {
            if (f != null && f.exists()) Fs.deleteRec(f);
        } catch (Throwable ignored) {
        }
    }

    private static String abs(String p) {
        try {
            return new File(p).getAbsolutePath();
        } catch (Throwable t) {
            return Str.nz(p);
        }
    }

    private static boolean inside(File dir, File f) {
        try {
            String a = dir.getAbsolutePath();
            String b = f.getAbsolutePath();
            return b.startsWith(a + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[表情包] " + message);
        } catch (Throwable ignored) {
        }
    }
}
