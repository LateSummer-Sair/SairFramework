import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.ai.DeepSeek;
import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.Caller;
import sair.v4.ctx.Ctx;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Th;
import sair.v4.qq.Api;
import sair.v4.qq.Ev;
import sair.v4.qq.MediaAttach;
import sair.v4.qq.MediaRender;
import sair.v4.skill.ConfView;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * <b>辅助识图的长生命期会话本体</b>（外挂技能 {@code 辅助识图} 的工具 {@code look} 的实现）。
 *
 * <h3>它是什么</h3>
 * <p>一台<b>进程内单例</b>的识图组件：自己持有一小段对话（第一条 system 是技能目录里的
 * {@code recognizer.md}，逐字 —— 外加一个<b>固定的"附加任务"块</b> {@link #STICKER_TRAILER_TASK}，
 * 顺序是"brief 逐字 + 追加块"，仍是同一条 system、同一次请求），
 * 只调 {@link DeepSeek#vision(JsonArray, JsonObject)}，
 * <b>永远不带工具表</b>（{@code opts} 里只有 {@code model} ⇒ {@code DeepSeek.vision} 把
 * {@code tools} 传 {@code null} ⇒ 请求体里连 {@code tools} 字段都没有）。所以它<b>没有手</b>：
 * "只描述不行动"是<b>构造性</b>的，不是靠提示词自觉。</p>
 *
 * <h3>长生命期的三条（缺一条就是假的）</h3>
 * <ol>
 *   <li><b>跨回合存活</b>：同一实例连续处理多张图，保留自己的对话尾巴（{@link #history}）；</li>
 *   <li><b>懒启动 + 空闲自退</b>：第一次 {@code look} 才建 worker（{@link Th#daemon(String, Runnable)}，
 *       线程名 {@value #WORKER_NAME}），队列空 {@code 空闲退出秒} 自己 {@code return} 并把
 *       {@code worker} 置空 ⇒ 技能热重载不留孤儿线程（这是 {@code mount\PLAN.md} 点名的那个坑）；</li>
 *   <li><b>重启不丢</b>：会话计数与去重账本落 {@code kv}（{@value #KV_SESSION} / {@value #KV_PREFIX}&lt;身份&gt;）。</li>
 * </ol>
 *
 * <h3>串行闸 = 1（构造性）</h3>
 * <p>单线程 worker + {@link ArrayBlockingQueue}（{@value #QUEUE_MAX}）：第 2 个请求在队列里等第 1 个。
 * 同一个身份的并发调用<b>共用同一个 {@link Task} 对象</b>（{@code ConcurrentHashMap.putIfAbsent} 分流，
 * 照 {@code qq\QuoteCache.run} 的写法）⇒ "同一身份只解析一次"是构造性的：不重复入队、不重复打网。</p>
 *
 * <h3>身份 → URL 的三级阶梯（绝不编）</h3>
 * <ol>
 *   <li><b>进程内身份索引</b>（{@code <id>#<seg> → url}）+ 基板入站登记簿
 *       {@link Host#inboundImageUrl(String)}（按 NapCat 的 {@code file} 值索引）⇒ 0 次网络；</li>
 *   <li>{@code h.napcat().call("get_msg", {message_id})} 的段里取第 {@code seg} 个 image 段的 {@code url}；</li>
 *   <li>拿不到 ⇒ <b>如实失败</b>（{@code gone}/{@code empty}/瞬时），绝不编 URL、绝不下载字节。</li>
 * </ol>
 *
 * <h3>账本（照 {@code qq\QuoteCache} 的形状抄）</h3>
 * <ul>
 *   <li>键 {@code mediax:look/<id>#<seg>}，值键序固定 {@code v,id,state,src,model,ts,text,chars,n}；
 *       正负<b>同键</b>，靠 {@code state} 区分（{@link #ST_OK}/{@link #ST_EMPTY}/{@link #ST_GONE}/
 *       {@link #ST_FAIL}/{@link #ST_PENDING}，与 {@code QuoteCache.ST_*} 同名同义）；</li>
 *   <li>TTL 逐字照抄 {@code QuoteCache.ttlMs()}：{@code min(mediaCacheHours×3600000,
 *       grouplogKeepDays×24×3600000)}（kv 表不被 {@code Store.maintain()} 清理 ⇒ 必须夹一刀）；
 *       {@code gone} <b>永不过期</b>（撤回是永久的）；</li>
 *   <li><b>瞬时失败不落 kv</b>：只在内存 {@link #cooldown} 里抑制 {@value #TRANSIENT_SUPPRESS_MS} ms；</li>
 *   <li>预算超限被丢弃时写一条 {@code state:pending}（它<b>不算命中</b>，下一轮照样重试）。</li>
 * </ul>
 *
 * <h3>只看配置面判"该不该有它"</h3>
 * <p>技能的 {@code op=look} 这条路拿不到 {@code Turn}（{@code Host} 没有 {@code state(...)}），
 * 所以门只读配置面：{@code mediaVision} + {@link sair.v4.ctx.CtxBuild#looksVisionCapable(String)}
 * （基板自己那个 1 参重载，注释逐字写明"给运行时编译的技能链进来的口"）——
 * <b>本类不维护第二套白名单</b>，也绝不改基板。</p>
 *
 * <p><b>但门现在只按策略判</b>：{@code mediaVision=relay}（基板新增的显式取值）时<b>直接放行</b>
 * （{@link #gateDeny}），不判"能不能看"、也不再要求 {@code resolveModel()} 非空 ——
 * 因为 relay 策略的语义就是"图不再原生挂进主模型请求、所有图都只能过看图手"；
 * 此时还按 {@code FB_FLASH} 拒，等于"提示词让她用图手、图手却拒她"=她永远看不了图。
 * 下面那条能力判据（{@code FB_FLASH}）只服务<b>原生模式</b>下的"别白花钱"。</p>
 *
 * <h3>表情包候选：唯一的生产者（甲方口径）</h3>
 * <p>甲方原话：<b>表情包收集是跟着看图手走的，是在看图时的一个顺带，而不是主动自动去监听每一个消息</b>。
 * 落点就在 {@link #run} 里：那<b>一次</b> {@link DeepSeek#vision(JsonArray, JsonObject)} 的回执里
 * 已经带了 trailer（{@link #STICKER_TRAILER_TASK} 要求它在描述最后另起一段给四行），
 * 于是"判定"是顺带拿到的，<b>不因此多一次调用、多一次网络</b>。</p>
 * <ol>
 *   <li>回执先剥 trailer：{@link #parseTrailer} 以**最后一个** {@link #STICKER_TRAILER_HEAD} 为界切出，
 *       <b>描述 = 正文</b>（回执形状仍是 {@code [识图·<模型>] <描述>}，账本里也只存正文）；</li>
 *   <li>解析出 {@code faces} / {@code 图中文字} / {@code 安全判定} 三项；<b>只有逐字"正常"才算安全</b>，
 *       读不到 / 写错 / faces 不在枚举里 ⇒ <b>不写候选</b>（fail-closed）；</li>
 *   <li>真的写那一行的是 {@link #writeCandidate}（键 {@link #CAND_PREFIX}，接口注释见那里）。</li>
 * </ol>
 */
public final class VisionAgent {

    // ================================================================ 冻结的形状

    /** 技能名（= 目录名 = md 名）。 */
    public static final String NAME = "辅助识图";
    /** kv 前缀（沿用冻结的 {@code mediax:} 命名空间）。 */
    public static final String KV_PREFIX = "mediax:look/";
    /** 会话 kv 键。 */
    public static final String KV_SESSION = KV_PREFIX + "session";
    /** 预算 kv 键。 */
    public static final String KV_BUDGET = KV_PREFIX + "budget";

    /** 状态：模型给出了描述（此时才有 {@code text}/{@code chars}/{@code n}）。 */
    public static final String ST_OK = "ok";
    /** 状态：取回来了但这条消息在那个 {@code seg} 上没有图片段 —— 不编。 */
    public static final String ST_EMPTY = "empty";
    /** 状态：已撤回/不存在（{@code retcode 1200}）—— 落负缓存，永不重试。 */
    public static final String ST_GONE = "gone";
    /** 状态：永久性失败（非 1200 的 {@code retcode≠0}）—— 落负缓存。 */
    public static final String ST_FAIL = "fail";
    /** 状态：这一轮没去看（预算超限丢弃）—— 不算命中，下一轮照样重试。 */
    public static final String ST_PENDING = "pending";

    /** 来源：技能进程内身份索引。 */
    public static final String SRC_INDEX = "index";
    /** 来源：基板入站登记簿（按 file 值）。 */
    public static final String SRC_INBOUND = "inbound";
    /** 来源：NapCat {@code get_msg}。 */
    public static final String SRC_GETMSG = "get_msg";
    /** 来源：还没有任何来源（{@code pending} 的记录就是这么写的）。 */
    public static final String SRC_NONE = "none";

    /** 机器文本的来源词（{@link MediaRender#machine} 的第一个参数）。 */
    public static final String MACHINE_SOURCE = "识图";

    // ---------------------------------------------------------------- 图片内容审查（禁图词表；只审"图片内容"这一层）
    // 甲方令（逐字）：**涉政、黄赌毒只针对图片内容审查，消息内容放宽的**。
    // ⇒ 这两类红线**不再拦聊天正文**（那是情绪 / 出站那条线的事），只在**模型对这张图的回执**上审：
    //   命中任一类 ⇒ ① 描述**不交给她**（回执换成 {@link #FB_IMG_BLOCK} 那一句固定的）；
    //   ② 给 `表情包` 的候选行**不写**。这两个输出是同一次 {@code run(Task)} 产出的
    //   （见 {@code run} / {@code fromLedger} 的两个调用点），所以**同一个判据函数**盖住两条路。
    // ★ 判据真源**只有配置里那两张表**（{@link #IMG_KEY_POLITICS} / {@link #IMG_KEY_VICE}，见 prompt.md）
    //   —— 本类里**一个词都没有**（改词只改数据），也没有除这两个键以外的任何开关。

    /** 图片审查的配置键之一（涉政类词表；**词表本体只在 prompt.md 里**）。 */
    public static final String IMG_KEY_POLITICS = "禁图-涉政";
    /** 图片审查的配置键之一（黄赌毒类词表；**词表本体只在 prompt.md 里**）。 */
    public static final String IMG_KEY_VICE = "禁图-黄赌毒";
    /** 拦截回执里的固定标签（回执形状 = {@code [识图·已拦截] …}）。 */
    public static final String IMG_BLOCK_TAG = "已拦截";
    /**
     * 拦截回执的**结构性兜底**（与 prompt.md 的 {@code 图片拦截} 一行**逐字相同**）。
     *
     * <p>它是**固定常量**（不含模型产出的任何一个字节），所以自己写方括号标签是安全的 ——
     * 这里刻意不走 {@link MediaRender#machine}：那条路是给"模型产出的正文"消毒/包装用的
     * （防模型自己伪造 {@code [识图·…]} 标签），而这一行**全字面**，不存在伪造面。</p>
     */
    public static final String FB_IMG_BLOCK = "[识图·" + IMG_BLOCK_TAG + "] 这张图的内容按红线不转述。";

    /** 取回队列长度（超了**丢弃不排队**）。 */
    public static final int QUEUE_MAX = 32;
    /** 瞬时失败的内存抑制窗口（不写 kv；防"一条坏身份反复解析"）。 */
    public static final long TRANSIENT_SUPPRESS_MS = 60000L;
    /** worker 线程名（探针按它数线程；也是"空闲自退"的可观测面）。 */
    public static final String WORKER_NAME = "v4-look";
    /** 一次识别最多记多少字符（正文上限；{@code chars} 记截断前的长度）。 */
    public static final int TEXT_MAX = 800;
    /** brief 文件名（技能自己目录里）。 */
    public static final String BRIEF_FILE = "recognizer.md";
    /** 段序上限（防"第 999999 张图"这种畸形）。 */
    public static final int SEG_MAX = 64;
    /** 取消息的 NapCat 动作。 */
    public static final String ACT_GET_MSG = "get_msg";
    /** 身份索引的容量上限（照 {@code qq.Inbound} 的 256 条量级）。 */
    public static final int INDEX_MAX = 256;
    /** 身份索引的默认存活（毫秒）：30 分钟 —— <b>与 {@code qq.Inbound} 同寿</b>。 */
    public static final long INDEX_TTL_MS = 30L * 60L * 1000L;

    // ---------------------------------------------------------------- 结构性兜底文案
    // prompt.md 是唯一配置源；这些只是"文案缺项/读不到"时的兜底（形状永远是 [look] ok=0 … 或 [识图·模型] …）。

    public static final String FB_HOST_NULL = "[look] ok=0 host=null";
    public static final String FB_FLASH = "[look] ok=0 你自己能看原图，不需要识图组件";
    public static final String FB_VISION_OFF = "[look] ok=0 图片能力被关（mediaVision=no）";
    public static final String FB_NO_CONF = "[look] ok=0 读不到配置面（conf=null），按拒绝处理";
    public static final String FB_NO_MODEL = "[look] ok=0 读不到主模型名（resolveModel 为空），按拒绝处理";
    public static final String FB_NO_ID = "[look] ok=0 要我看哪张图？给我 id（消息 id 的十进制串，例如 10001）";
    public static final String FB_NOT_NUM = "[look] ok=0 id 必须是消息 id 的十进制串：{id}";
    public static final String FB_URL = "[look] ok=0 只接受消息身份（id/seg），不接受 URL";
    public static final String FB_SEG = "[look] ok=0 seg 必须 ≥ 0（0 = 这条消息里第一张图）";
    public static final String FB_OP = "[look] ok=0 不支持的 op：{op}（只有 look）";
    public static final String FB_NO_BRIEF = "[look] ok=0 读不到 recognizer.md（{path}），没有 brief 不敢冒充识图组件";
    public static final String FB_EMPTY = "[look] ok=0 这条消息里没有可取的第 {seg} 张图（state={state}）";
    public static final String FB_GONE = "[look] ok=0 这张图取不回来了（消息已撤回或不存在）";
    public static final String FB_FAIL = "[look] ok=0 取这张图被拒了（{error}）";
    public static final String FB_NOT_CONNECTED = "[look] ok=0 NapCat 没有连接，取不到这张图";
    public static final String FB_TRANSIENT = "[look] ok=0 这张图这次没取回来：{error}（不算永久失败，60 秒后可再试）";
    public static final String FB_BUDGET = "[look] ok=0 识图预算用尽（{why}），这张图我没看";
    public static final String FB_QUEUE_FULL = "[look] ok=0 识图队列满了（{n} 条在等），这张图我没看（不排队）";
    public static final String FB_TIMEOUT = "[look] ok=0 识图超时（{sec} 秒没回来），这张图我没看";
    public static final String FB_MODEL_FAIL = "[look] ok=0 识图模型没看成：{error}";
    public static final String FB_EMPTY_RESULT = "[look] ok=0 识图模型没给出内容";
    public static final String FB_ERROR = "[look] ok=0 识图异常：{error}";
    /** 默认问题缺失时的结构性兜底（英文，不当人话文案）。 */
    public static final String ASK_FALLBACK = "describe this image";

    // ---------------------------------------------------------------- 表情包候选（跨技能接口）
    // 甲方口径（逐字）：**表情包收集是跟着看图手走的，是在看图时的一个顺带，而不是主动自动去监听每一个消息**。
    // ⇒ 候选只有**这一个生产者**：本类 run(Task) 在**那一次**视觉调用拿到回执之后顺手判定、顺手写一行。
    //   看得见的三条推论：① 入站钩子（noteInbound）依旧只写内存索引，**不判、不写候选**；
    //   ② 不新增任何模型/网络调用（全仓唯一的视觉调用就是 run(Task) 里那一处）；
    //   ③ 没被"看过"的图永远不进池子 —— 收集量 = 她真的看了多少张图。

    /**
     * 候选行的 kv 前缀：{@code sticker:cand/<md5>}。
     *
     * <p><b>这是跨技能接口键</b>：<b>生产者 = 本技能（{@code 辅助识图}）</b>、
     * <b>消费者 = {@code 表情包}</b> —— 键名 / 字段 / 判定口径<b>必须两边一致</b>，
     * 改键名得先过技能线经理。</p>
     *
     * <ul>
     *   <li>它和 {@code sticker:collect:seen:*} / {@code sticker:collect:vision:*} <b>同属"kv 小账本"</b>，
     *       但<b>归属与清理方不同</b>：那两个在 {@code 表情包} 内部自产自消（钩子登记 + {@code sweep} 清），
     *       本键是<b>别人写、{@code 表情包} 清</b>（消费方做"读到即删 + 超 {@code ts} 过期删"两条清理）。</li>
     *   <li><b>本类绝不删这个键</b>（清理只由消费者做）—— 生产者只管写一行、覆盖一行。</li>
     *   <li><b>生产者侧天然幂等</b>：同一个 {@code id#seg} 第二次进来会先命中账本
     *       （{@code lookOne} 第 ④ 步、或 {@code run(Task)} 开头的闸内双检）<b>直接返回</b>，
     *       根本走不到写候选那一步 ⇒ 同一身份不会重复写、不会多出行。
     *       即便 {@code refresh=true} 或账本写失败导致再走一遍，也只是<b>同键覆盖同一个值</b>
     *       （键由 URL/file 的 md5 决定，不随调用次数增长）。</li>
     * </ul>
     */
    public static final String CAND_PREFIX = "sticker:cand/";

    /** trailer 头行的键（不含冒号）：切分界按"这个键 + 中文冒号/半角冒号"认，与消费方读行的口径一致。 */
    public static final String STICKER_TRAILER_KEY = "表情数量";
    /** trailer 的头（逐字）：以**最后一个**它出现处为界切出 trailer。 */
    public static final String STICKER_TRAILER_HEAD = STICKER_TRAILER_KEY + "：";
    /** brief 与追加块之间的分隔（两个换行；追加块本体逐字，一个字不改）。 */
    public static final String STICKER_TRAILER_SEP = "\n\n";
    /**
     * <b>固定的"附加任务"追加块</b>（逐字贴进来，一个字不改；由技能线经理给定）。
     *
     * <p>它进的是<b>第一条 system</b>：{@code recognizer.md 逐字 + 本块} —— 请求仍然是那<b>一次</b>
     * {@code ai.vision(...)}，没有第二次调用（本条就是本次改动的全部意义）。</p>
     */
    public static final String STICKER_TRAILER_TASK = "## [附加任务：表情包候选判定]\n"
            + "除了上面的描述，**必须**在回答的最后另起一段，严格按下面四行给出（这一段的格式不许变、不许加别的字）：\n"
            + "表情数量：<无 / 一张 / 多张>\n"
            + "图中文字：<把图中出现的文字原样写出来；没有文字就写「无」>\n"
            + "内容描述：<一句话，20 字以内>\n"
            + "安全判定：<正常 或 违规>\n"
            + "判定口径：软色情、Cos/Coser 图、政治敏感、色情、黄赌毒、暴力血腥、游戏截图一律算「违规」。";

    /** trailer「表情数量」的三个取值（**只有这三个算解析成功**，别的值 fail-closed）。 */
    public static final String FACES_NONE = "无";
    public static final String FACES_ONE = "一张";
    public static final String FACES_MANY = "多张";
    /** trailer「安全判定」的两个取值（**只有逐字"正常"才算 safe=true**）。 */
    public static final String SAFE_WORD_OK = "正常";
    public static final String SAFE_WORD_BAD = "违规";
    /** trailer「图中文字」写「无」= 图里没有文字 ⇒ {@code text_len = 0}。 */
    public static final String TEXT_NONE = "无";
    /** 候选行 {@code desc} 的字符上限：超了截断（末尾补「…」）并加 {@code "cut":true}。 */
    public static final int CAND_DESC_MAX = 200;

    /**
     * <b>配置值</b> {@code mediaVision=relay} ⇒ 所有图都只能过看图手（本类门里判的就是<b>这一个字面量</b>）。
     *
     * <p><b>别把两个口径混起来</b>：</p>
     * <ul>
     *   <li><b>配置值</b> = {@code mediaVision}（技能面读 {@code ConfView.get("mediaVision","auto")}；
     *       正式取值 {@code auto} / {@code no|off|false} / {@code yes|on|true} / <b>{@code relay}</b>）；</li>
     *   <li><b>事实值</b> = {@code models.mode}（{@code off|native|relay}，由<b>基板</b>
     *       {@code CtxBuild.visionMode(...)} 算出来挂进事实行）—— 那是基板的面，本类不读、也不判它。
     *       ⇒ {@code mediaVision=relay} 会不会映射成 {@code mode=relay}，由基板那一版 jar 决定
     *       （以基板交付为准），<b>与这里的放行判定无关</b>。</li>
     * </ul>
     *
     * <p><b>别名</b>：基板侧还接受别名 {@code look}（{@code CtxBuild} 里 {@code relay}/{@code look}
     * 都算 relay）—— <b>技能面只认 {@code relay}</b>，不实现 {@code look} 这个别名（它落在
     * {@code relay} 之外的字面量里 ⇒ 本类按"怪值"处理：原来的能力判据照旧生效）。</p>
     */
    public static final String VISION_MODE_RELAY = "relay";

    // ================================================================ 单例

    private static volatile VisionAgent INSTANCE;

    /** 进程内单例（懒建；技能热重载 = 新类加载器 ⇒ 新单例，旧的那个会自己空闲退出）。 */
    public static VisionAgent get() {
        VisionAgent a = INSTANCE;
        if (a != null) return a;
        synchronized (VisionAgent.class) {
            if (INSTANCE == null) INSTANCE = new VisionAgent();
            return INSTANCE;
        }
    }

    // ================================================================ 入站身份登记（on_message 钩子）

    /**
     * <b>入站身份登记</b>（{@code on_message} 钩子<b>唯一</b>做的事，由 {@code LookTool.on(...)} 转发）。
     *
     * <p>它把"这条消息里第几张图 = 哪条 URL/file"在<b>到达那一刻</b>记进进程内身份索引，
     * 于是阶梯① 是真的（不是死代码）：一条刚到的图片消息，第一次 {@code look} 就是
     * {@code src=index}、<b>0 次 {@code get_msg}</b>。</p>
     *
     * <h3>纪律（{@code on_message} 在<b>入站同步路径</b>上，任何昂贵操作都会拖慢所有消息落库）</h3>
     * <ul>
     *   <li><b>零 I/O</b>：不读不写 kv、不读文件、<b>不走网</b>、不碰基板自己的 {@code qq.Inbound}；</li>
     *   <li><b>零副作用</b>：不改 payload 的 {@code _handled}/{@code _reply}（那会改变基板还要不要自己回）、
     *       不回话、不注入、不派活；</li>
     *   <li><b>零异常面</b>：整段 try/catch 吞掉（入站路径上绝不抛、也不打日志刷屏——
     *       真出事由基板自己的 {@code Skills.invokeHook} 记一行）；</li>
     *   <li><b>零重活</b>：每条消息 O(image 段数) 次内存 put + <b>amortized O(1)</b> 剪枝（见 {@link #trimIndex}）。</li>
     * </ul>
     *
     * <p><b>登记口径</b>（与 {@code qq.Inbound.remember} 同源）：只认 {@code type=image} 段；
     * 段序 = <b>该消息里第几个 image 段（0 起）</b>；只登记 url/file <b>至少一个非空</b>的段；
     * 非消息事件 / 没有 {@code message_id} 的 payload 直接返回。</p>
     *
     * <p><b>顺手多记两个数（表情包候选的防线②/③）</b>：发送者 {@code user_id} 与事件里的
     * {@code self_id}（配方与 {@code 表情包\StickerCollect.java:206} 同源：{@code Ev.userId()}/{@code Ev.selfId()}）。
     * 写候选前用它判"这张图是不是她自己发的"（见 {@link #candSenderAllowed}）——
     * 钩子在这一步<b>仍然不判、不写候选、不碰 kv</b>，只把两个 long 记进内存索引。</p>
     *
     * <p><b>防线①（构造性，不靠本方法自觉）</b>：本钩子只接 {@code post_type=message}
     * （下面那行 {@code "message".equals(pt)}）；她自己发出去的消息是 OneBot 的
     * {@code message_sent}，基板在<b>入站之前</b>就把它丢掉了（{@code src\sair\v4\QqGateway.java:163-170}，
     * 见 {@code 表情包\StickerCollect.java:198-200} 的同一条引注）⇒ 它<b>根本到不了这里</b>，
     * 也就永远不会进身份索引。防线②/③ 是"上游万一改了口径"时不塌的那一层。</p>
     *
     * @param payload {@code on_message} 的事件 JSON（基板给的原始 OneBot 事件）
     */
    public static void noteInbound(JsonObject payload) {
        try {
            if (payload == null) return;
            long mid = J.l(payload, "message_id", 0L);
            if (mid <= 0L) return;
            String pt = Str.trim(J.s(payload, "post_type", ""));
            if (Str.has(pt) && !"message".equals(pt)) return;      // 只认消息事件
            Ev ev = Ev.parse(payload);
            List<JsonObject> segs = ev.segments();
            if (segs == null || segs.isEmpty()) return;
            // 防线②/③ 的两个数（只登记，不判定；拿不到就是 0 ⇒ 后面按"证明不了是别人"处理）
            long userId = ev.userId();
            long selfId = ev.selfId();
            VisionAgent a = get();
            long now = System.currentTimeMillis();
            // 段序口径（跨线接口对账，不许隐式混用）：这里数的是**技能 seg**（这条消息里第几个 image 段，
            // **0 起**），与去重身份 id#seg 同一个口径；基板 qq\MediaAttach.java:71 的 Skip.ord 是
            // "第几张图（**1 起**）" ⇒ 两边要换算**只有一条**：ord = seg + 1（显式 +1）。
            // 本方法**不读**基板的 ord，所以这里不需要换算；变量名刻意不叫 ord，免得与基板 1 起口径撞名。
            int segNo = -1;                                        // 第几个 image 段（技能 seg，0 起）
            for (int i = 0; i < segs.size(); i++) {
                JsonObject seg = segs.get(i);
                if (seg == null) continue;
                if (!"image".equals(Str.lower(Str.trim(J.s(seg, "type", ""))))) continue;
                segNo++;
                JsonObject d = J.sub(seg, "data");
                if (d == null) continue;
                String url = Str.trim(J.s(d, "url", ""));
                String file = Str.trim(J.s(d, "file", ""));
                if (url.isEmpty() && file.isEmpty()) continue;      // 只登记至少一个非空的段
                a.register(mid + "#" + segNo, url, file, now, userId, selfId);
            }
        } catch (Throwable ignored) {
            // 入站路径：绝不抛、绝不刷屏
        }
    }

    /**
     * 一条身份登记（{@code url}/{@code file} 都可能为空串；至少一个非空的段才登记）。
     *
     * <p>{@code userId}/{@code selfId} 是入站钩子顺手记下的两个 long（表情包候选的防线②/③）：
     * 由 worker 补登身份（拿到 URL 之后）时<b>只知道身份、不知道发送者</b>，所以那里传 0/0，
     * {@link #register} 会把入站记下的那一份<b>留着不抹</b>。</p>
     */
    private static final class Idx {
        final String url;
        final String file;
        final long ts;
        /** 发这条消息的人（{@code Ev.userId()}；拿不到 = 0）。 */
        final long userId;
        /** 本机账号（{@code Ev.selfId()}；拿不到 = 0）。 */
        final long selfId;

        Idx(String url, String file, long ts, long userId, long selfId) {
            this.url = url;
            this.file = file;
            this.ts = ts;
            this.userId = userId;
            this.selfId = selfId;
        }

        boolean live(long now, long ttl) {
            return ttl <= 0L || now - ts <= ttl;
        }
    }

    /** 登记（或覆盖）一条身份，并顺手剪枝（发送者未知；已有登记里的发送者会被保留）。 */
    private void register(String identity, String url, String file, long now) {
        register(identity, url, file, now, 0L, 0L);
    }

    /**
     * 登记（或覆盖）一条身份，并顺手剪枝。
     *
     * <p>发送者两个数（{@code userId}/{@code selfId}）传 0/0 时表示"这次不知道发送者"
     * （worker 补登路径就是这样）：此时<b>沿用旧登记里的值</b>，绝不把入站钩子记下的那一份抹成 0 ——
     * 否则"看图前先补登一条 URL"这一下会把防线②/③ 的证据清掉。</p>
     */
    private void register(String identity, String url, String file, long now, long userId, long selfId) {
        if (Str.blank(identity)) return;
        if (userId <= 0L && selfId <= 0L) {
            Idx old = idx.get(identity);
            if (old != null) {
                userId = old.userId;
                selfId = old.selfId;
            }
        }
        idx.put(identity, new Idx(Str.nz(url), Str.nz(file), now, userId, selfId));
        idxOrder.add(identity);
        trimIndex(now);
    }

    /**
     * 剪枝：按<b>登记顺序</b>丢掉"过期的"与"超容量的"，amortized O(1)（正常路径上一步都不动）。
     * <p>{@code idx.remove(k, cur)} 只在"这一格还是这一个对象"时删 ⇒ 同一个身份被重新登记
     * （新的 {@code ts}）时，队列里那个影子键不会把新登记误删。</p>
     */
    private void trimIndex(long now) {
        String k;
        while ((k = idxOrder.peek()) != null) {
            Idx cur = idx.get(k);
            if (cur == null) {
                idxOrder.poll();
                continue;
            }
            if (cur.live(now, indexTtlMs) && idx.size() <= INDEX_MAX) break;
            idxOrder.poll();
            idx.remove(k, cur);
        }
    }

    /** 查一次身份（过期即当没有，并顺手摘掉那一格 —— 与 {@code Inbound.urlOfFile} 同口径）。 */
    private Idx idxGet(String identity, long now) {
        Idx e = idx.get(identity);
        if (e == null) return null;
        if (!e.live(now, indexTtlMs)) {
            idx.remove(identity, e);
            return null;
        }
        return e;
    }

    // ================================================================ 状态（每实例一套）

    private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<Task>(QUEUE_MAX);
    /** 同一身份同一时刻只有一个任务在飞（"只解析一次"的构造性闸）。 */
    private final ConcurrentHashMap<String, Task> inFlight = new ConcurrentHashMap<String, Task>();
    /**
     * 进程内<b>身份索引</b>：{@code "<msg_id>#<image 段序>" → {url, file, ts}}。
     *
     * <p>它是契约里"阶梯①"的本体：由 {@code on_message} 钩子（{@link #noteInbound(JsonObject)}）
     * 在<b>入站那一刻</b>登记，worker 解析成功后也会补登一条 —— 所以"这条消息刚来过"的图
     * 第一次 {@code look} 就能命中（<b>0 次网络</b>），TTL 与 {@code qq.Inbound} 同寿（30 分钟）。</p>
     */
    private final ConcurrentHashMap<String, Idx> idx = new ConcurrentHashMap<String, Idx>();
    /** 登记顺序（剪枝用；amortized O(1)，**绝不遍历大表**）。 */
    private final ConcurrentLinkedQueue<String> idxOrder = new ConcurrentLinkedQueue<String>();
    /** 瞬时失败过的身份（60 秒内不再试）—— <b>只在内存</b>，重启即清。 */
    private final ConcurrentHashMap<String, Long> cooldown = new ConcurrentHashMap<String, Long>();
    /** 会话尾巴（旧轮只留文字、不带图）。 */
    private final Deque<JsonObject> history = new ArrayDeque<JsonObject>();

    private final AtomicLong turns = new AtomicLong();
    private final AtomicLong looks = new AtomicLong();
    private final AtomicLong drops = new AtomicLong();
    private final AtomicLong idleExits = new AtomicLong();

    private final Object sessionLock = new Object();
    private final Object budgetLock = new Object();

    private volatile Thread worker;
    private volatile boolean stopped;
    private volatile long idleMs = 120000L;
    /** 身份索引的有效期（由每次调用读到的 {@code prompt.md 索引TTL秒} 刷新；默认与 Inbound 同寿）。 */
    private volatile long indexTtlMs = INDEX_TTL_MS;

    /** 只有探针用：把"空闲自退"的判定缩短（线上永远不调）。 */
    public void setIdleMsForTest(long ms) {
        if (ms > 0L) idleMs = ms;
    }

    // ================================================================ 对外唯一入口

    /**
     * 看一条消息里的一张图，返回<b>机器描述</b>（{@code [识图·<模型>] <描述>}）或一句如实的失败。
     *
     * @param h       技能宿主（<b>必须有</b>；null ⇒ 直接回 {@link #FB_HOST_NULL}）
     * @param id      消息 id 的<b>十进制串</b>（{@code grouplog.msg_id} / 图片消息的 {@code message_id}）
     * @param seg     这条消息里第几个<b>图片段</b>（0 起；去重身份 = {@code id#seg}）
     * @param ask     要它看图里的什么（空 ⇒ prompt.md 的默认问题）
     * @param refresh true = 忽略账本重新识别一次（不绕过预算闸）
     */
    public String lookOne(Host h, String id, int seg, String ask, boolean refresh) {
        return lookOne(h, id, seg, ask, refresh, Cfg.load(h));
    }

    /** {@link #lookOne(Host, String, int, String, boolean)}，用调用方已经读好的配置。 */
    public String lookOne(Host h, String id, int seg, String ask, boolean refresh, Cfg c) {
        if (h == null) return FB_HOST_NULL;
        if (c == null) c = Cfg.load(h);
        try {
            // ① 门：主模型自己会看图（或图片能力被关）时明确拒绝
            String gate = gateDeny(h, c);
            if (gate != null) return gate;

            // ② brief 必须读得到（读不到就不假装是识图组件）
            String briefPath = briefPath(h);
            String brief = readBrief(briefPath);
            if (Str.blank(brief)) return c.msg("读不到brief", FB_NO_BRIEF, "{path}", briefPath);

            Store store = h.store();
            String identity = id + "#" + seg;
            long now = System.currentTimeMillis();
            String model = modelOf(h, c);
            ConfView conf = confOf(h);
            idleMs = c.idleExitMs;
            indexTtlMs = c.indexTtlMs;
            long mid = num(id);
            if (mid <= 0L) return c.msg("身份非数字", FB_NOT_NUM, "{id}", id);

            // ③ 会话：这一轮进来了（turns+1；命中账本也算"进来过"）
            bumpSession(store, true, 0L, model);

            // ④ 账本（正/负同键）
            JsonObject rec = refresh ? null : ledger(store, identity, now, conf);
            if (rec != null && !ST_PENDING.equals(J.s(rec, "state", ""))) {
                return fromLedger(c, rec, seg);
            }

            // ⑤ 瞬时抑制（内存；不落 kv）
            if (!refresh && recentTransient(identity)) {
                return c.msg("瞬时失败", FB_TRANSIENT, "{error}", "suppressed_60s");
            }

            // ⑥ 预算闸（超了丢弃、不排队、写一条 pending）
            String why = budget(store, now, c);
            if (why != null) {
                save(store, pendingRec(identity, now));
                drops.incrementAndGet();
                dim("身份 " + identity + " 预算用尽（" + why + "），丢弃不排队");
                return c.msg("预算用尽", FB_BUDGET, "{why}", why);
            }

            // ⑦ 串行闸 + 队列（同一身份共用同一个任务）
            Task t = new Task(identity, mid, seg, Str.trim(ask).isEmpty() ? defaultAsk(c) : Str.trim(ask),
                    model, brief, c, refresh);
            t.host = h;
            t.store = store;
            t.ai = h.ai();
            t.subject = h.subject();
            t.napcatDeny = napcatDeny(h, c);

            Task running = inFlight.putIfAbsent(identity, t);
            if (running != null) {
                t = running;                                    // 同一个身份：等同一个结果，不重复入队
            } else {
                if (!queue.offer(t)) {
                    inFlight.remove(identity, t);
                    drops.incrementAndGet();
                    dim("身份 " + identity + " 队列已满（" + QUEUE_MAX + " 条在等），丢弃不排队");
                    return c.msg("队列满", FB_QUEUE_FULL, "{n}", String.valueOf(QUEUE_MAX));
                }
                ensureWorker();
            }

            // ⑧ 等结果（有界：worker 是单线程，第 2 个请求要在队列里等第 1 个）
            boolean done;
            try {
                done = t.done.await(c.waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                done = false;
            }
            if (!done) return c.msg("超时", FB_TIMEOUT, "{sec}", String.valueOf(c.waitMs / 1000L));
            if (Str.blank(t.result)) return c.msg("异常", FB_ERROR, "{error}", "empty_result");
            return t.result;
        } catch (Throwable t) {
            warn("识图异常：" + t);
            return c.msg("异常", FB_ERROR, "{error}", String.valueOf(t));
        }
    }

    // ================================================================ 门（只看配置面）

    /**
     * 门：<b>现在只按策略判</b>，不再"能看图就一律拒"。
     *
     * <p>判据全部来自配置面 —— 技能工具拿不到 {@code Turn}（{@code Host} 没有 {@code state(...)}），
     * 所以这里刻意与 {@code data\skills\视觉识图\VisionSkill.java:130-131} 用<b>同一句现成写法</b>：
     * {@code conf.resolveModel()} + {@link sair.v4.ctx.CtxBuild#looksVisionCapable(String)}。
     * <b>不新造第二套判定逻辑</b>；判不出来（读不到 conf / 模型名为空 / 判据抛异常）一律<b>拒</b>
     * （与全仓 {@code Host.need} 的"判不出来 = 拒"同口径）。</p>
     *
     * <h3>三个取值三种结论（{@code mediaVision}）</h3>
     * <ol>
     *   <li>{@code no} / {@code off} / {@code false} ⇒ 拒（{@link #FB_VISION_OFF}，与本次改动前逐字相同）；</li>
     *   <li>{@code relay}（基板本次新增的显式取值）⇒ <b>放行</b>（{@code return null}）：
     *       <b>不判"能不能看"、也不再要求模型名非空</b>。理由：relay 策略的语义就是
     *       "图不再原生挂进主模型请求、<b>所有路径都只能过看图手</b>"；
     *       此时若还按 {@link #FB_FLASH} 拒，就是"提示词让她用图手、图手却拒她" ⇒ <b>她永远看不了图</b>；</li>
     *   <li>{@code auto} / {@code yes} / 怪值 ⇒ <b>与改动前逐字相同</b>（能看 ⇒ {@link #FB_FLASH}；
     *       模型名空 ⇒ {@link #FB_NO_MODEL}）。</li>
     * </ol>
     * <p><b>{@link #FB_FLASH} 只服务原生模式下的"别白花钱"</b>（能看图的模型自己就看得到，
     * 再派识图组件是白花钱 + 多一层转述）；在 relay 策略下这条必须让路 —— 两件事不是一回事：
     * "我能不能看"（白名单真值）与"允不允许我把图挂上去"（策略）。</p>
     * <p>{@code conf} 读不到 ⇒ <b>仍然拒</b>（fail-closed，判不出来 = 拒）—— 这一条一个字不改。</p>
     */
    private static String gateDeny(Host h, Cfg c) {
        ConfView cf;
        try {
            cf = h.conf();
        } catch (Throwable t) {
            cf = null;
        }
        if (cf == null) return c.msg("无配置", FB_NO_CONF);
        // 技能面拿到的是只读配置视图（ConfView）—— 没有 mediaVision() 这个具名 getter，
        // 所以按键取值；默认 "auto" 与 Conf.mediaVision() 的默认逐字一致。
        String mv = Str.lower(Str.trim(cf.get("mediaVision", "auto")));
        if ("no".equals(mv) || "off".equals(mv) || "false".equals(mv)) {
            return c.msg("图片能力被关", FB_VISION_OFF);
        }
        // ★ 策略 = relay ⇒ 放行（不判能力、也不再要求模型名）：所有图都只能过看图手，
        //   这一档下"模型自己会看图"根本不成立（图压根不挂进主模型请求）。
        //   注意这两句判的都是**配置值** mediaVision（不是事实值 models.mode）；
        //   基板侧另接受别名 look，技能面只认 relay（见 VISION_MODE_RELAY 的注释）。
        if (VISION_MODE_RELAY.equals(mv)) return null;
        String self = Str.trim(cf.resolveModel());
        if (self.isEmpty()) return c.msg("无模型", FB_NO_MODEL);
        boolean capable;
        try {
            // R3 / 3a：与基板 CtxBuild.selfHasVision（src\sair\v4\ctx\CtxBuild.java:250-252）**同一个默认值来源** ——
            // 改走 ConfView.visionModels()（缺键 = "deepseek-flash"），与 h.conf().get("visionModels","") 的
            // "缺键传空串"形式差异消失；两处（本处与 data\skills\视觉识图\VisionSkill.java:131）同源。
            // 判据本身仍是 CtxBuild.looksVisionCapable（硬白名单 + 配置追加项），**不新造第二套判定**。
            capable = sair.v4.ctx.CtxBuild.looksVisionCapable(self, cf.visionModels(), Str.trim(cf.visionModel()));
        } catch (Throwable t) {
            capable = true;                                     // 判不出来 = 拒
        }
        if (capable) return c.msg("拒答flash", FB_FLASH);
        return null;
    }

    /**
     * 取图的 NapCat 动作判定在<b>调用者线程</b>上算：不让 worker 线程去替谁判（那是换主体的洞）。
     *
     * <p>判定的是 NapCat 动作自己的 op（{@code napcat.<动作名>}）：管控表里没给这个身份授权
     * （空 / Ban）就拒，拒绝原文由 {@code h.need} 给（带 {@code [权限阻断]} 前缀）；
     * 她自己（{@code SYSTEM}）与主人恒全权。</p>
     */
    private static String napcatDeny(Host h, Cfg c) {
        try {
            if (!h.napcatConnected()) return c.msg("没连上", FB_NOT_CONNECTED);
        } catch (Throwable t) {
            return c.msg("没连上", FB_NOT_CONNECTED);
        }
        try {
            return h.need("napcat." + ACT_GET_MSG);
        } catch (Throwable t) {
            return c.msg("瞬时失败", FB_TRANSIENT, "{error}", String.valueOf(t));
        }
    }

    // ================================================================ worker（单线程、队列 32、空闲自退）

    /** 一个识别请求（同身份共用同一个对象 ⇒ 并发调用也只有一个在飞）。 */
    private static final class Task {
        final String identity;
        final long msgId;
        final int seg;
        final String ask;
        final String model;
        final String brief;
        final Cfg cfg;
        /** true = 忽略账本重新识别一次（<b>闸内双检也必须认它</b>，否则 refresh 会被自己拦掉）。 */
        final boolean refresh;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result = "";

        Host host;
        Store store;
        DeepSeek ai;
        Caller subject;
        String napcatDeny = "";

        Task(String identity, long msgId, int seg, String ask, String model, String brief, Cfg cfg,
             boolean refresh) {
            this.identity = identity;
            this.msgId = msgId;
            this.seg = seg;
            this.ask = ask;
            this.model = model;
            this.brief = brief;
            this.cfg = cfg;
            this.refresh = refresh;
        }
    }

    private void ensureWorker() {
        Thread w = worker;
        if (w != null && w.isAlive()) return;
        synchronized (this) {
            if (worker != null && worker.isAlive()) return;
            if (stopped) return;
            final VisionAgent self = this;
            Thread t = Th.daemon(WORKER_NAME, new Runnable() {
                @Override
                public void run() {
                    self.loop();
                }
            });
            worker = t;
            t.start();
        }
    }

    private void loop() {
        while (!stopped) {
            Task t;
            try {
                t = queue.poll(idleMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (t == null) {
                synchronized (this) {
                    if (!queue.isEmpty()) continue;             // 刚好又来了活
                    worker = null;                              // 空闲自退：下次请求再懒建
                    idleExits.incrementAndGet();
                    dim("worker 空闲 " + (idleMs / 1000L) + " 秒，自行退出（下次请求再懒建）");
                    return;
                }
            }
            try {
                run(t);
            } catch (Throwable e) {
                warn("worker 异常（已忽略）：" + e);
            }
        }
    }

    /** 一个任务：闸内双检 ⇒ 解析身份 ⇒ 调模型（无工具）⇒ 落账本 ⇒ 记会话。 */
    private void run(Task t) {
        Ctx.Scope sc = null;
        try {
            if (t.subject != null) sc = Ctx.of(t.subject);       // 换线程不换主体（闸门读的就是它）
            long now = System.currentTimeMillis();
            JsonObject rec = t.refresh ? null : ledger(t.store, t.identity, now, confOf(t.host));   // 闸内双检（第一个刚写完缓存的情况）
            if (rec != null && !ST_PENDING.equals(J.s(rec, "state", ""))) {
                t.result = fromLedger(t.cfg, rec, t.seg);
                return;
            }
            Url u = resolveUrl(t);
            if (Str.blank(u.url)) {
                if (u.transientFail) {
                    markTransient(t.identity);
                } else {
                    save(t.store, failRec(t.identity, u.state, u.src, now));
                }
                t.result = u.message;
                return;
            }

            // ③ 调模型：只给 model，不给工具表（构造性"没有手"）
            if (t.ai == null) {
                markTransient(t.identity);
                t.result = t.cfg.msg("模型失败", FB_MODEL_FAIL, "{error}", "ai=null");
                return;
            }
            JsonArray messages = new JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            // recognizer.md 逐字（不 trim、不改一个字）+ 固定的"附加任务"块 ⇒ **仍是这一条 system、这一次请求**。
            // 追加块要求它在描述最后另起一段给四行（表情数量/图中文字/内容描述/安全判定）——
            // 这就是"表情包候选跟着看图手走、在看图时顺带拿到"的全部机制，不额外打网、不额外调模型。
            sys.addProperty("content", t.brief + STICKER_TRAILER_SEP + STICKER_TRAILER_TASK);
            messages.add(sys);
            synchronized (history) {
                for (JsonObject old : history) messages.add(old);
            }
            java.util.List<String> urls = new java.util.ArrayList<String>();
            urls.add(u.url);
            messages.add(Msg.userWithImages(t.ask, urls));

            JsonObject opts = new JsonObject();
            if (Str.has(t.model)) opts.addProperty("model", t.model);

            Res r;
            try {
                r = t.ai.vision(messages, opts);
            } catch (Throwable e) {
                markTransient(t.identity);
                t.result = t.cfg.msg("模型失败", FB_MODEL_FAIL, "{error}", String.valueOf(e));
                return;
            }
            if (r == null || !r.ok()) {
                String err = r == null ? "null" : Str.oneLine(Str.trim(r.error));
                markTransient(t.identity);
                t.result = t.cfg.msg("模型失败", FB_MODEL_FAIL, "{error}", err);
                return;
            }
            String text = Str.trim(r.text());
            if (text.isEmpty()) {
                markTransient(t.identity);                       // 模型侧失败是瞬时的：不落 kv
                t.result = t.cfg.msg("空结果", FB_EMPTY_RESULT);
                return;
            }
            // ① 剥 trailer + ② 解析三个值：**同一个回执里顺带拿到**（没有第二次调用、没有第二次网络）。
            //    剥完的描述才是给主模型的"描述"，回执形状一个字没变：仍是 [识图·<模型>] <描述>。
            Verdict v = parseTrailer(text);
            String desc = v.desc;                                // = trailer 之前的正文（trim）
            if (Str.blank(desc)) {
                // 切完描述为空（模型只回了 trailer）：走 FB_EMPTY_RESULT 那条失败语义，**不写候选**。
                markTransient(t.identity);
                t.result = t.cfg.msg("空结果", FB_EMPTY_RESULT);
                return;
            }
            // ★★ 图片内容审查（**唯一插入点**：回执成形之前、任何落盘之前）★★
            // 下面这几行会一次性产出**两样东西**：① 交给她的一行 [识图·<模型>] <描述>（账本命中那条路
            // 也照产）；② 给 `表情包` 的 sticker:cand/<md5> 候选行（writeCandidate）。两者是**同一次
            // run(Task)** 的两个输出 ⇒ 在这一处判完，两条路一起被盖住（入站钩子只登记内存索引，
            // 从不产描述、不写候选 ⇒ 它那条路本来就没有描述可漏）。
            // 命中 ⇒ 描述**不交出去**、候选行**不写**、账本**不落**（拦截结果不落盘），回执换固定那一句；
            // 读不到词表 ⇒ 放行 + 一行 warn（fail-open，见 imgGate）。
            String blocked = imgGate(t.cfg, text, t.identity);
            if (blocked != null) {
                t.result = blocked;
                return;
            }
            String used = Str.has(r.modelUsed) ? Str.trim(r.modelUsed) : t.model;
            // 账本只存**剥掉 trailer 的正文**：命中账本那条路（fromLedger）也照 MediaRender.machine 产回执
            // ⇒ 不剥的话，第二次调用会把 trailer 原样吐给她。chars 随之是正文的长度。
            save(t.store, okRec(t.identity, u.src, used, now, desc));
            bumpSession(t.store, false, 1L, used);
            remember(t.ask, desc, t.cfg.windowTurns);             // 会话尾巴同样只留正文（不带 trailer）
            writeCandidate(t, u, v, used, now);                   // 只在"真识别成功（state=ok）"这条路上写
            String machine = MediaRender.machine(MACHINE_SOURCE, used, desc);
            if (Str.blank(machine)) {
                t.result = t.cfg.msg("空结果", FB_EMPTY_RESULT);
                return;
            }
            dim("身份 " + t.identity + " src=" + u.src + " model=" + used + " chars=" + desc.length()
                    + " cand=" + (v.ok ? "yes" : "no"));
            t.result = machine;
        } catch (Throwable e) {
            warn("识图任务异常（已忽略）：" + e);
            if (Str.blank(t.result)) t.result = t.cfg.msg("异常", FB_ERROR, "{error}", String.valueOf(e));
        } finally {
            if (sc != null) sc.close();
            inFlight.remove(t.identity);
            t.done.countDown();
        }
    }

    // ================================================================ 身份 → URL

    /** 解析结果（要么 {url} 可用，要么一句如实的话）。 */
    private static final class Url {
        String url = "";
        /** 这条身份对应的 NapCat {@code file} 值（候选行要用；拿不到 = 空串，绝不编）。 */
        String file = "";
        String src = SRC_NONE;
        String state = "";
        String message = "";
        boolean transientFail = false;
    }

    private Url resolveUrl(Task t) {
        Url u = new Url();
        long now = System.currentTimeMillis();
        // ① 进程内身份索引（on_message 钩子在入站那一刻登记的；这是契约里的"阶梯①"）
        Idx e = idxGet(t.identity, now);
        if (e != null && MediaAttach.usable(e.url)) {
            u.url = e.url;
            u.file = e.file;
            u.src = SRC_INDEX;
            return u;
        }
        // ①b 基板入站登记簿（按 NapCat 的 file 值索引；事件里那条 URL 是跨机可取的那一条）
        if (e != null && Str.has(e.file)) {
            String ev = inbound(t, e.file);
            if (MediaAttach.usable(ev)) {
                register(t.identity, ev, e.file, now);
                u.url = ev;
                u.file = e.file;
                u.src = SRC_INBOUND;
                return u;
            }
        }
        // ② get_msg
        if (Str.has(t.napcatDeny)) {
            u.transientFail = true;
            u.message = t.napcatDeny;
            return u;
        }
        Api nap = t.host == null ? null : t.host.napcat();
        if (nap == null) {
            u.transientFail = true;
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", "no_napcat_api");
            return u;
        }
        JsonObject resp;
        try {
            resp = nap.call(ACT_GET_MSG, J.obj("message_id", Long.valueOf(t.msgId)));
        } catch (Throwable ce) {
            u.transientFail = true;
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", String.valueOf(ce));
            return u;
        }
        if (resp == null) {
            u.transientFail = true;
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", "null_response");
            return u;
        }
        if (!Api.ok(resp)) {
            long rc = J.l(resp, "retcode", 0L);
            String err = Str.oneLine(Str.trim(Api.error(resp)));
            if (rc == 1200L || gone(err)) {                      // 已撤回/不存在：永久事实 ⇒ 负缓存
                u.state = ST_GONE;
                u.src = SRC_GETMSG;
                u.message = t.cfg.msg("已撤回", FB_GONE);
                return u;
            }
            if (rc != 0L) {                                      // 非 1200 的 retcode≠0：永久性拒绝
                u.state = ST_FAIL;
                u.src = SRC_GETMSG;
                u.message = t.cfg.msg("永久失败", FB_FAIL, "{error}", err);
                return u;
            }
            u.transientFail = true;                              // 非 JSON / 空响应：瞬时
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", err);
            return u;
        }
        JsonObject data = J.sub(resp, "data");
        if (data == null) {
            u.transientFail = true;
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", "no_data");
            return u;
        }
        JsonArray segs = J.list(data, "message");
        // 段序对账（跨线口径）：seen 数的是**技能 seg**（image 段 0 起；-1 起数所以 ++ 后 = 第几个 image 段），
        // 与 t.seg 直接就比、**不做 ±1** —— 基板那个 1 起的 ord（MediaAttach.java:71）在本方法里不参与，
        // 要跟基板对账才写 ord = seg + 1，别在这里偷偷 +1。
        int seen = -1;
        String url = "";
        String segFile = "";
        if (segs != null) {
            for (int i = 0; i < segs.size(); i++) {
                JsonElement el = segs.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject seg = el.getAsJsonObject();
                if (!"image".equals(Str.lower(Str.trim(J.s(seg, "type", ""))))) continue;
                seen++;
                if (seen != t.seg) continue;
                JsonObject d = J.sub(seg, "data");
                url = d == null ? "" : Str.trim(J.s(d, "url", ""));
                segFile = d == null ? "" : Str.trim(J.s(d, "file", ""));
                break;
            }
        }
        if (seen < t.seg) {                                      // 这条消息里没有第 seg 张图
            u.state = ST_EMPTY;
            u.src = SRC_GETMSG;
            u.message = t.cfg.msg("看不到", FB_EMPTY,
                    "{seg}", String.valueOf(t.seg), "{state}", ST_EMPTY);
            return u;
        }
        if (!MediaAttach.usable(url) && Str.has(segFile)) {
            // 基板自己在 mediaFacts 里也是这么兜的：段里没有可用 URL 时问入站登记簿
            String ev = inbound(t, segFile);
            if (MediaAttach.usable(ev)) {
                url = ev;
                u.src = SRC_INBOUND;
            }
        }
        if (!MediaAttach.usable(url)) {                          // 段在、URL 取不到：瞬时（稍后可能就有）
            u.transientFail = true;
            u.message = t.cfg.msg("瞬时失败", FB_TRANSIENT, "{error}", "no_usable_url");
            return u;
        }
        register(t.identity, url, segFile, now);                 // 补登身份索引（下次同身份 0 次网络）
        u.url = url;
        u.file = segFile;                                        // 候选行要用（拿不到就是空串）
        if (Str.blank(u.src) || SRC_NONE.equals(u.src)) u.src = SRC_GETMSG;
        return u;
    }

    private static String inbound(Task t, String file) {
        try {
            return t.host == null ? "" : Str.trim(t.host.inboundImageUrl(file));
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean gone(String err) {
        String e = Str.nz(err);
        return e.indexOf("撤回") >= 0 || e.indexOf("不存在") >= 0;
    }

    // ================================================================ 账本

    /** 账本读取（过期 ⇒ 当未命中并顺手删掉那一格；{@code gone} 永不过期）。 */
    private JsonObject ledger(Store store, String identity, long now, ConfView conf) {
        String k = KV_PREFIX + identity;
        String v;
        try {
            v = kv(store, k, "");
        } catch (Throwable t) {
            return null;
        }
        if (Str.blank(v)) return null;
        JsonObject o = J.obj(v);
        if (o == null) {
            kvDelete(store, k);
            return null;
        }
        String st = J.s(o, "state", "");
        if (ST_GONE.equals(st)) return o;                        // 撤回是永久的
        if (ST_PENDING.equals(st)) return o;                     // 不算命中（调用方会再试）
        long ttl = ttlMs(conf);
        long ts = J.l(o, "ts", 0L);
        if (ttl > 0L && ts > 0L && now - ts > ttl) {
            kvDelete(store, k);
            return null;
        }
        return o;
    }

    /**
     * 有效 TTL（毫秒；{@code 0} = 永不过期）——<b>逐字照抄</b> {@code qq\QuoteCache.ttlMs()}：
     * 名义值是 {@code mediaCacheHours}（默认 168 = 7 天），再夹到 {@code grouplogKeepDays×24}
     * （{@code Store.maintain()} 完全不清 {@code kv}，不夹这一刀，账本会活得比群聊记录还久）。
     */
    public static long ttlMs(ConfView conf) {
        try {
            // 技能面只有只读配置视图（ConfView）⇒ 按键取值；默认值与 Conf 的具名 getter 逐字一致
            // （mediaCacheHours 默认 168、grouplogKeepDays 默认 Conf.DEF_GROUPLOG_KEEP_DAYS = 30）。
            int h = conf == null ? 168 : conf.getInt("mediaCacheHours", 168);
            if (h <= 0) return 0L;
            int keep = conf == null ? Conf.DEF_GROUPLOG_KEEP_DAYS
                    : conf.getInt("grouplogKeepDays", Conf.DEF_GROUPLOG_KEEP_DAYS);
            if (keep > 0) h = Math.min(h, keep * 24);
            return h * 3600000L;
        } catch (Throwable t) {
            return 168L * 3600000L;
        }
    }

    /** 账本骨架（键序固定，便于逐字断言）：{@code v,id,state,src,model,ts}（+ {@code text,chars,n} 只在 ok 时）。 */
    private static JsonObject base(String identity, String state, String src, String model, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("id", identity);
        o.addProperty("state", state);
        o.addProperty("src", src);
        if (Str.has(model)) o.addProperty("model", model);
        if (ts > 0L) o.addProperty("ts", ts);
        return o;
    }

    private static JsonObject okRec(String identity, String src, String model, long ts, String text) {
        JsonObject o = base(identity, ST_OK, src, model, ts);
        String raw = Str.nz(text);
        o.addProperty("text", raw.length() <= TEXT_MAX ? raw : raw.substring(0, TEXT_MAX) + "…");
        o.addProperty("chars", raw.length());                    // 截断前的长度
        o.addProperty("n", 1);
        return o;
    }

    private static JsonObject failRec(String identity, String state, String src, long ts) {
        return base(identity, Str.has(state) ? state : ST_FAIL, Str.has(src) ? src : SRC_NONE, "", ts);
    }

    private static JsonObject pendingRec(String identity, long ts) {
        return base(identity, ST_PENDING, SRC_NONE, "", ts);
    }

    private void save(Store store, JsonObject rec) {
        try {
            kvSet(store, KV_PREFIX + J.s(rec, "id", ""), J.json(rec));
        } catch (Throwable t) {
            warn("账本写失败（这一轮照样有回执）：" + t);
        }
    }

    /** 从账本记录产出一句回执（{@code ok} 才走 {@link MediaRender#machine}）。 */
    private String fromLedger(Cfg c, JsonObject rec, int seg) {
        String st = J.s(rec, "state", "");
        // 观测（非判定，只多一行打印）：命中账本 ⇒ **0 次网络、0 次模型调用**。放在 fromLedger 内部是为了
        // "一处覆盖两条命中路"：① {@code lookOne(...)} 入队前先查账本；② {@code run(Task)} 开头的闸内双检（同身份第二个任务）。
        // ⚠ 同一个字段名 `src=` 承载两种含义，别误读成"URL 来自 ledger 这一级"：
        //   · 本行 `src=ledger` = "**未解析 URL、直接命中账本**"；
        //   · run() 里 "身份 … src=" 那行的 `src=` = "**URL 从哪一级解析来的**"。
        // 这一格的**允许取值集合**（前三个由 {@code resolveUrl(...)} 阶梯产出，第四个是本行）：
        //   index（进程内身份索引）/ inbound（基板入站登记簿）/ get_msg（NapCat 取回）/ **ledger（命中账本）**。
        // 只做观测 —— 不动判定/阈值/回执文本/预算计数；fromLedger 的返回值一个字不改。
        dim("身份 " + J.s(rec, "id", "") + " src=ledger state=" + st
                + " model=" + J.s(rec, "model", "") + " chars=" + J.l(rec, "chars", 0L));
        if (ST_OK.equals(st)) {
            // ★ 图片内容审查（**同一个判据函数** {@link #imgGate}）：账本里那条描述也要过同一道闸 ——
            //   否则本次改动**之前**就已经落进账本的旧描述（或改表之后才命中的词）会在缓存期内被
            //   原样转述出去：审查点只挡"新回执"是挡不住它的。命中 ⇒ 同样只回那一句固定的，
            //   描述**一个字都不出去**（账本条目本身不动：拦住的是"交付"，不是"存过"）。
            String blocked = imgGate(c, J.s(rec, "text", ""), J.s(rec, "id", ""));
            if (blocked != null) return blocked;
            String m = MediaRender.machine(MACHINE_SOURCE, J.s(rec, "model", ""), J.s(rec, "text", ""));
            return Str.blank(m) ? c.msg("空结果", FB_EMPTY_RESULT) : m;
        }
        if (ST_EMPTY.equals(st)) {
            return c.msg("看不到", FB_EMPTY, "{seg}", String.valueOf(seg), "{state}", st);
        }
        if (ST_GONE.equals(st)) return c.msg("已撤回", FB_GONE);
        if (ST_FAIL.equals(st)) return c.msg("永久失败", FB_FAIL, "{error}", J.s(rec, "src", ""));
        if (ST_PENDING.equals(st)) return c.msg("预算用尽", FB_BUDGET, "{why}", "pending");
        return c.msg("异常", FB_ERROR, "{error}", "state=" + st);
    }

    // ================================================================ 图片内容审查（禁图词表；只审"图片内容"这一层）
    // 甲方令（逐字）：**涉政、黄赌毒只针对图片内容审查，消息内容放宽的**。
    // ⇒ 这两类红线**不再拦聊天正文**（那是情绪 / 出站那条线的事）：本类**没有任何出站/正文审查**，
    //   审查面只有一处 —— **模型对这张图的回执**（见 imgGate 的两个调用点：run / fromLedger）。
    // ★ 判据真源**只有 prompt.md 里那两张表**（IMG_KEY_POLITICS / IMG_KEY_VICE）：
    //   本类里一个词都没有（改词只改数据），也没有除这两个配置键以外的任何开关。
    // ★ **fail-open**：读不到词表（缺行 / 整份 prompt.md 读不到）⇒ 放行 + 一行 warn。
    //   识图是功能，不能因为读不到词表就把所有图都禁了。

    /** 一次图片审查的结论（纯数据；便于离线夹具逐字断言）。 */
    static final class ImgVerdict {
        /** 命中的类目名（= 那张表的配置键）；空串 = 放行。 */
        String category = "";
        /** 读不到词表的那几类（**fail-open 的类目**；调用方只按类目名记一行 warn，一个词都不带）。 */
        String missing = "";
    }

    /**
     * <b>图片内容审查</b>（纯函数：无 I/O、无副作用、不起模型、不联网、不落盘）。
     *
     * <p>判据 = {@code text} 里出现任一张 {@code 禁图-*} 表里的任一词形（<b>子串、大小写不敏感</b>）。
     * 审的是<b>模型对这张图的整条回执</b>：描述正文 <b>+</b> trailer 那四行 ——
     * trailer 里的「图中文字」也是<b>图片内容</b>（图上的字），所以一并审，不做只审正文的收窄。</p>
     *
     * <p>两张表都读不到（{@code c == null} / 缺行）⇒ 不拦，只把缺的那几类记进
     * {@link ImgVerdict#missing}（fail-open 的证据面，由 {@link #imgGate} 记一行 warn）。</p>
     *
     * @param c    本技能的配置（两张表就在里面；{@code null} = 读不到 ⇒ 全放行）
     * @param text 模型对这张图的回执原文
     */
    static ImgVerdict imgScreen(Cfg c, String text) {
        ImgVerdict v = new ImgVerdict();
        String politics = c == null ? "" : c.imgPolitics;
        String vice = c == null ? "" : c.imgVice;
        StringBuilder miss = new StringBuilder();
        if (Str.blank(politics)) miss.append(IMG_KEY_POLITICS);
        if (Str.blank(vice)) {
            if (miss.length() > 0) miss.append(',');
            miss.append(IMG_KEY_VICE);
        }
        v.missing = miss.toString();
        if (Str.blank(text)) return v;                           // 没有可审的正文 ⇒ 放行（调用方另有空结果语义）
        if (hitTable(text, politics)) v.category = IMG_KEY_POLITICS;
        else if (hitTable(text, vice)) v.category = IMG_KEY_VICE;
        return v;
    }

    /** 一句话里出现表里任一词形（子串；**大小写不敏感**）：表按逗号分隔（半角/全角都认）。 */
    private static boolean hitTable(String text, String table) {
        if (Str.blank(text) || Str.blank(table)) return false;
        String low = Str.lower(text);
        for (String w : table.split("[,，]")) {
            String x = Str.lower(Str.trim(w));
            if (x.isEmpty()) continue;
            if (low.indexOf(x) >= 0) return true;
        }
        return false;
    }

    /**
     * <b>审查 + 记日志的唯一一处接线</b>（{@code run(Task)} 与 {@code fromLedger} 两个调用点都调它）。
     *
     * <ul>
     *   <li><b>读不到词表</b> ⇒ 一行 warn（只报类目名，fail-open：这几类不拦），返回 {@code null}（放行）；</li>
     *   <li><b>命中</b> ⇒ 一行 warn（<b>只带类目名与图 id（= id#seg），不带描述正文</b>），
     *       返回固定回执 {@link #FB_IMG_BLOCK}（prompt.md 的 {@code 图片拦截} 一行，缺项用同字面兜底）；</li>
     *   <li>其余 ⇒ {@code null}（放行，不打任何日志）。</li>
     * </ul>
     *
     * @return {@code null} = 放行；非 null = <b>该回给她的那一行</b>（拦截回执）
     */
    private String imgGate(Cfg c, String text, String identity) {
        Cfg cc = c == null ? new Cfg() : c;
        ImgVerdict v = imgScreen(cc, text);
        if (Str.has(v.missing)) {
            warn("图片审查词表读不到，这几类按放行（fail-open）：" + v.missing);
        }
        if (Str.has(v.category)) {
            warn("图片拦截 类目=" + v.category + " id=" + identity);
            return cc.msg("图片拦截", FB_IMG_BLOCK);
        }
        return null;
    }

    // ================================================================ 表情包候选（trailer 解析 + 写一行）
    // 甲方口径：候选是**看图时的一个顺带**（跟着看图手走），不是"主动自动监听每一个消息"。
    // 所以这一段全部是**纯计算 + 一次 kv 写**：零模型调用、零网络、零新增配置键。
    // 唯一的调用点是 run(Task) 里那一次 ai.vision 成功之后（state=ok 的那条路）——见 writeCandidate 的注释。

    /** trailer 解析结果（{@code ok=false} ⇒ 一个字都不写；描述照旧返回，回执形状不变）。 */
    static final class Verdict {
        /** trailer 之前的正文（已 trim）：调用方据此产回执，账本也只存它。 */
        String desc = "";
        /** 表情数量（只有 {@link #FACES_NONE}/{@link #FACES_ONE}/{@link #FACES_MANY} 算解析成功）。 */
        String faces = "";
        /** 图中文字原文（「无」= 没有文字）。 */
        String text = "";
        /** 图中文字的字符数（{@link #countChars} 口径；「无」⇒ 0）。 */
        int textLen = 0;
        /** 只有逐字"正常"才是 true。 */
        boolean safe = false;
        /** 三项（表情数量 / 图中文字 / 安全判定）都读得到且认得 ⇒ 才允许写候选。 */
        boolean ok = false;
    }

    /**
     * <b>剥 trailer + 解析三个值</b>（纯函数：无 I/O、无副作用、不起模型、不联网）。
     *
     * <ol>
     *   <li><b>切</b>：以**最后一个** {@link #STICKER_TRAILER_HEAD} 出现处为界（半角冒号也认，
     *       与消费方 {@code 表情包\StickerCollect.field} 读行时"两种冒号都认"同口径）；
     *       一处都没有 ⇒ 没有 trailer（{@code ok=false}，但描述 = 整条回复 ⇒ 回执与改动前逐字相同）；</li>
     *   <li><b>描述</b> = 界之前的正文（{@code trim}）；正文为空 ⇒ {@code ok=false}
     *       （调用方走 {@link #FB_EMPTY_RESULT} 那条失败语义，不写候选）；</li>
     *   <li><b>三项</b>：{@code 表情数量} ∈ {无,一张,多张}、{@code 图中文字} 那一行读得到、
     *       {@code 安全判定} ∈ {正常,违规} —— <b>缺一个就不写候选</b>（fail-closed）；
     *       {@code safe = 逐字"正常"}（"违规" ⇒ false；读不到 / 写错 ⇒ 整个 {@code ok=false}，不是 safe=false）。</li>
     * </ol>
     *
     * <p>注：追加块里那行「内容描述」是给模型的格式脚手架 —— 本接口的 {@code desc} 取的是
     * <b>正文</b>（上面第 ② 条的冻结口径），trailer 里那一行本类**不读**。</p>
     */
    static Verdict parseTrailer(String reply) {
        Verdict v = new Verdict();
        String full = Str.trim(reply);
        if (Str.blank(full)) return v;                            // 空回复（调用方另有 FB_EMPTY_RESULT）
        int cut = lastTrailerHead(full);
        v.desc = Str.trim(cut < 0 ? full : full.substring(0, cut));
        if (cut < 0 || Str.blank(v.desc)) return v;
        String tr = full.substring(cut);
        String faces = trailerField(tr, STICKER_TRAILER_KEY);
        String text = trailerField(tr, "图中文字");
        String safeWord = trailerField(tr, "安全判定");
        boolean facesOk = FACES_NONE.equals(faces) || FACES_ONE.equals(faces) || FACES_MANY.equals(faces);
        boolean safeOk = SAFE_WORD_OK.equals(safeWord) || SAFE_WORD_BAD.equals(safeWord);
        if (!facesOk || !safeOk || text == null) return v;        // 读不到 / 写错 ⇒ 不写候选
        v.faces = faces;
        v.text = Str.trim(text);
        v.textLen = TEXT_NONE.equals(v.text) ? 0 : countChars(v.text);
        v.safe = SAFE_WORD_OK.equals(safeWord);
        v.ok = true;
        return v;
    }

    /** 最后一个 trailer 头的位置（{@link #STICKER_TRAILER_KEY} + 中文冒号或半角冒号）；没有 = -1。 */
    private static int lastTrailerHead(String s) {
        int best = -1;
        for (int i = s.indexOf(STICKER_TRAILER_KEY); i >= 0; i = s.indexOf(STICKER_TRAILER_KEY, i + 1)) {
            int j = i + STICKER_TRAILER_KEY.length();
            if (j < s.length() && (s.charAt(j) == '：' || s.charAt(j) == ':')) best = i;
        }
        return best;
    }

    /**
     * 取"四行格式"里某一行 {@code 键：值} 的值 —— 读法**逐字照抄** {@code 表情包\StickerCollect.field}
     * （去掉 {@code *} 与反引号、中文/半角冒号都认、值 {@code Str.trim}），两边对同一份回执必须读到同一个值。
     *
     * <p><b>那一行读不到 = {@code null}</b>（与"读到了但值是空的"区分开）：前者 fail-closed 不写候选。</p>
     */
    static String trailerField(String text, String key) {
        if (Str.blank(text) || Str.blank(key)) return null;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = Str.trim(raw).replace("*", "").replace("`", "");
            int i = line.indexOf('：');
            if (i < 0) i = line.indexOf(':');
            if (i <= 0) continue;
            if (key.equals(Str.trim(line.substring(0, i)))) return Str.trim(line.substring(i + 1));
        }
        return null;
    }

    /**
     * 图中文字计数：去掉空白与常见标点后数字符 —— <b>与表情包技能同口径</b>。
     *
     * <p>实现**逐字照抄** {@code data\skills\表情包\StickerCollect.java} 的 {@code countChars}
     * （该技能 {@code applyVerdict} 里就是用它算 {@code text_len} 的）；<b>不许在这里新造口径</b>：
     * 同一个串喂给两边必须得到同一个数。</p>
     */
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

    /**
     * 防线②/③ 的判定（纯函数：便于离线自证）：<b>只有能证明"发图的人不是本机账号"才允许写候选</b>。
     *
     * <p>配方与 {@code 表情包\StickerCollect.java:206} 同源（{@code selfId > 0 && userId == selfId}
     * ⇒ 那是她自己发的）；这里取了<b>更严的那一侧</b>：{@code userId} / {@code selfId} 任一读不到
     * （= 0，例如身份索引已过期、或这条身份是进程重启之后才补登的）⇒ <b>也判"不许写"</b> ——
     * 工单写明"fail-closed 方向是不写"，证明不了是别人就不往池子里塞。</p>
     *
     * <p>代价（照实写）：<b>没被入站钩子登记过的老图不会被收成候选</b>（身份索引 TTL 30 分钟）。
     * 这与甲方口径的字面语义一致：收集跟着"看图"走、也只是看图时的一个顺带。</p>
     */
    static boolean candSenderAllowed(long userId, long selfId) {
        return selfId > 0L && userId > 0L && userId != selfId;
    }

    /** {@code desc} 的规整结果（单行 + ≤ {@link #CAND_DESC_MAX} 字符）。 */
    static final class Cut {
        String text = "";
        /** true = 真的截断过（候选行末尾会多一个 {@code "cut":true}）。 */
        boolean cut = false;
    }

    /**
     * {@code desc} 的规整（纯函数）：**压成单行**（{@code Str.oneLine}：所有空白 → 空格，
     * kv 值是单行 JSON，不留裸换行 / 制表）+ **截到 {@link #CAND_DESC_MAX} 字符以内**。
     *
     * <p>超长时末尾补一个「…」并置 {@code cut=true} ⇒ 结果长度**恰好 ≤ 200**（不是 201）。
     * 长度按 UTF-16 码元算（与 {@link #TEXT_MAX} 那处同口径）；截断点若落在代理对中间会退一格
     * （不把一个 emoji 劈成半个）。{@code text_len} 不受这条影响：它是判定用的数字，按
     * {@link #countChars} 口径算完就存数字。</p>
     */
    static Cut candDesc(String s) {
        Cut c = new Cut();
        String one = Str.oneLine(Str.nz(s));
        if (one.length() <= CAND_DESC_MAX) {
            c.text = one;
            return c;
        }
        int n = CAND_DESC_MAX - 1;                                 // 留一格给「…」⇒ 结果 ≤ 200
        if (n > 0 && Character.isHighSurrogate(one.charAt(n - 1))) n--;
        c.text = one.substring(0, n) + "…";
        c.cut = true;
        return c;
    }

    /** 候选键的 md5 —— <b>与 {@code 表情包\StickerCollect.md5} 同配方</b>：{@code Fs.md5(Utf8(src))}。 */
    static String md5Of(String s) {
        try {
            return Fs.md5(Str.nz(s).getBytes(Fs.UTF8));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 候选行本体（纯函数：便于离线自证键序）。
     *
     * <p>键序<b>固定</b>为 {@code v,src,url,file,faces,text_len,safe,desc,id,model,ts}
     * （跨技能接口，两边一致）；{@code desc} 真被截断过时<b>在最后</b>多一个 {@code "cut":true}
     * （消费者不要求它存在、也不许把它当必填）。</p>
     */
    static JsonObject candRow(String src, String url, String file, Verdict v, String id, String model, long ts) {
        Cut d = candDesc(v == null ? "" : v.desc);
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("src", Str.nz(src));
        o.addProperty("url", Str.nz(url));
        o.addProperty("file", Str.nz(file));
        o.addProperty("faces", v == null ? "" : Str.nz(v.faces));
        o.addProperty("text_len", v == null ? 0 : v.textLen);
        o.addProperty("safe", v != null && v.safe);
        o.addProperty("desc", d.text);
        o.addProperty("id", Str.nz(id));
        o.addProperty("model", Str.nz(model));
        o.addProperty("ts", ts);
        if (d.cut) o.addProperty("cut", true);
        return o;
    }

    /**
     * <b>写一行候选</b>（键 {@code sticker:cand/<md5>}）——本次改动里**唯一**的新 kv 写点。
     *
     * <p>它是"表情包收集跟着看图手走"的落点：调用点只有 {@link #run} 里那一次视觉调用成功之后，
     * 所以<b>只在 {@code state=ok}（真识别成功）且 trailer 解析成功时写</b>；
     * {@code gone}/{@code empty}/{@code fail}/瞬时失败那几条路根本走不到这里
     * （它们在校验之前就 return 了，见 {@link #run} 的前半段）。</p>
     *
     * <p>三道防线（她自己的图绝不入池）：</p>
     * <ol>
     *   <li><b>①（构造性）</b>入站钩子只收 {@code post_type=message}，而 {@code message_sent}
     *       在基板入站之前就被丢了（{@code QqGateway.java:163-170}）⇒ 她自己的消息
     *       <b>根本登记不进身份索引</b>；</li>
     *   <li><b>②/③</b>写之前判 {@link #candSenderAllowed}：证明不了"发图的人不是本机账号"
     *       ⇒ 不写（fail-closed 方向 = 不写）；</li>
     *   <li>消费者（{@code 表情包}）那边还会再保留一道 —— 本类只保证生产者这一侧不漏。</li>
     * </ol>
     *
     * <p><b>幂等</b>：同一 {@code id#seg} 第二次进来先命中账本直接返回，走不到这里；
     * 就算 {@code refresh=true} 或账本写失败再走一遍，也只是同键覆盖（键由 src 的 md5 决定）。</p>
     *
     * <p><b>本类不删这个键</b>：清理只由消费者做（读到即删 + 超 {@code ts} 过期删）。</p>
     */
    private void writeCandidate(Task t, Url u, Verdict v, String model, long now) {
        try {
            if (t == null || u == null || v == null || !v.ok) return;   // 解析没成功 ⇒ 不写
            Store store = t.store;
            if (store == null) return;                                  // 没有 kv ⇒ 不写
            Idx e = idxGet(t.identity, now);
            long userId = e == null ? 0L : e.userId;
            long selfId = e == null ? 0L : e.selfId;
            if (!candSenderAllowed(userId, selfId)) {                    // 防线②/③
                dim("身份 " + t.identity + " 候选不写：发送者不可证为他人（idx="
                        + (e == null ? "miss" : userId + "/" + selfId) + "）");
                return;
            }
            String url = Str.trim(u.url);
            String file = Str.trim(u.file);
            String src = Str.has(url) ? url : file;
            if (!Str.has(src)) return;                                   // 连 src 都没有 ⇒ 不编
            String md5 = md5Of(src);
            if (!Str.has(md5)) return;
            kvSet(store, CAND_PREFIX + md5, J.json(candRow(src, url, file, v, t.identity, model, now)));
            dim("身份 " + t.identity + " 候选已写 " + CAND_PREFIX + md5
                    + " faces=" + v.faces + " text_len=" + v.textLen + " safe=" + v.safe);
        } catch (Throwable ex) {
            warn("候选写失败（不影响这一轮回执）：" + ex);
        }
    }

    // ================================================================ 会话 / 预算（kv）

    /**
     * 会话计数（{@code mediax:look/session}）：{@code turns} = 进来过几轮（含命中账本的），
     * {@code looks} = 真调过几次模型。这是"它活着"以及"重启不丢"的可观测面。
     */
    private void bumpSession(Store store, boolean isTurn, long looksAdd, String model) {
        if (store == null) return;
        synchronized (sessionLock) {
            JsonObject old = J.obj(kv(store, KV_SESSION, ""));
            if (old == null) old = new JsonObject();
            long t = J.l(old, "turns", 0L) + (isTurn ? 1L : 0L);
            long l = J.l(old, "looks", 0L) + (looksAdd > 0L ? looksAdd : 0L);
            JsonObject o = new JsonObject();
            o.addProperty("v", 1);
            o.addProperty("ts", System.currentTimeMillis());
            o.addProperty("turns", t);
            o.addProperty("looks", l);
            o.addProperty("model", Str.has(model) ? model : J.s(old, "model", ""));
            try {
                kvSet(store, KV_SESSION, J.json(o));
            } catch (Throwable e) {
                warn("会话写失败（不影响这一轮）：" + e);
            }
            turns.set(t);
            this.looks.set(l);
        }
    }

    /**
     * 预算闸（每分钟 / 每日，值写在技能的 {@code prompt.md} 里）。超了返回原因串（调用方丢弃该次请求）。
     * 计数按 {@code day} 串翻页、按 60 秒窗口滚 —— 重启不清零（下次读 kv 接着算）。
     */
    private String budget(Store store, long now, Cfg c) {
        if (store == null) return null;
        synchronized (budgetLock) {
            JsonObject b = J.obj(kv(store, KV_BUDGET, ""));
            if (b == null) b = new JsonObject();
            String day = Str.day();
            long n = J.l(b, "n", 0L);
            long min = J.l(b, "min", 0L);
            long minN = J.l(b, "minN", 0L);
            if (!day.equals(J.s(b, "day", ""))) n = 0L;
            if (min <= 0L || now - min >= 60000L) {
                min = now;
                minN = 0L;
            }
            String why = null;
            if (c.perDay > 0L && n >= c.perDay) why = "day=" + n + "/" + c.perDay;
            else if (c.perMin > 0L && minN >= c.perMin) why = "min=" + minN + "/" + c.perMin;
            JsonObject o = new JsonObject();
            o.addProperty("day", day);
            o.addProperty("n", why == null ? n + 1L : n);
            o.addProperty("min", min);
            o.addProperty("minN", why == null ? minN + 1L : minN);
            try {
                kvSet(store, KV_BUDGET, J.json(o));
            } catch (Throwable e) {
                warn("预算写失败（这一轮照样走）：" + e);
            }
            return why;
        }
    }

    /** 会话尾巴（旧轮只留文字、不带图：图只在它自己那一次请求里出现过，历史里重发是多付钱）。 */
    private void remember(String ask, String text, int windowTurns) {
        synchronized (history) {
            history.addLast(Msg.user(Str.nz(ask)));
            history.addLast(Msg.assistant(Str.nz(text)));
            int max = Math.max(2, (windowTurns > 0 ? windowTurns : 8) * 2);
            while (history.size() > max) history.removeFirst();
        }
    }

    // ================================================================ brief / 模型 / 配置

    /** brief 的路径（技能自己目录里；读不到就把路径如实写进回执）。 */
    static String briefPath(Host h) {
        try {
            File dir = h.skillDir();
            if (dir == null) return new File(NAME, BRIEF_FILE).getPath();
            return new File(dir, BRIEF_FILE).getPath();
        } catch (Throwable t) {
            return new File(NAME, BRIEF_FILE).getPath();
        }
    }

    /** {@code recognizer.md} 逐字读出来（每次调用重读：改完即生效）；读不到返回空串。 */
    private static String readBrief(String path) {
        try {
            String s = Fs.read(new File(path), "");
            // 硬规定：brief **逐字**进第一条 system ⇒ 这里不 trim（只把"整份是空白"当读不到）
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 这次请求要用的模型：技能的 {@code 视觉模型} 优先，空则基板的 {@code conf.visionModel()}。 */
    private static String modelOf(Host h, Cfg c) {
        if (Str.has(c.model)) return Str.trim(c.model);
        try {
            ConfView cf = h.conf();
            return cf == null ? "" : Str.trim(cf.visionModel());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String defaultAsk(Cfg c) {
        return Str.has(c.ask) ? Str.trim(c.ask) : ASK_FALLBACK;
    }

    /** 账本 TTL 用的配置面（技能没有 conf 字段 ⇒ 每次现取；取不到就用默认，与 QuoteCache 同默认）。 */
    private static ConfView confOf(Host h) {
        try {
            return h == null ? null : h.conf();
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 抑制 / kv / 日志

    private void markTransient(String identity) {
        cooldown.put(identity, Long.valueOf(System.currentTimeMillis()));
        dim("身份 " + identity + " 瞬时失败（" + (TRANSIENT_SUPPRESS_MS / 1000L) + " 秒内不再试）");
    }

    private boolean recentTransient(String identity) {
        Long t = cooldown.get(identity);
        if (t == null) return false;
        if (System.currentTimeMillis() - t.longValue() > TRANSIENT_SUPPRESS_MS) {
            cooldown.remove(identity);
            return false;
        }
        return true;
    }

    private static String kv(Store store, String k, String def) {
        return store == null ? def : store.kv(k, def);
    }

    private static void kvSet(Store store, String k, String v) {
        if (store != null) store.kvSet(k, v);
    }

    private static void kvDelete(Store store, String k) {
        if (store != null) store.kvDelete(k, false);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(Str.trim(s));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void dim(String line) {
        try {
            Out o = OUT;
            if (o != null) o.dim("[" + NAME + "] " + line);
        } catch (Throwable ignored) {
        }
    }

    private void warn(String line) {
        try {
            Out o = OUT;
            if (o != null) o.warn("[" + NAME + "] " + line);
        } catch (Throwable ignored) {
        }
    }

    /** 日志出口（技能自己没有 Out 字段，这里由 {@link LookTool} 每次调用前塞一个现成的）。 */
    static volatile Out OUT;

    // ================================================================ 诊断（探针/排障）

    /** worker 线程还活着吗（"空闲自退"的可断言面）。 */
    public boolean workerAlive() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    /** 空闲自退发生过几次。 */
    public long idleExitCount() { return idleExits.get(); }

    /** 队列里还剩几条在等。 */
    public int queueSize() { return queue.size(); }

    /** 现在有几个身份在飞。 */
    public int inFlightCount() { return inFlight.size(); }

    /** 进程内身份索引里有几条（探针断言"钩子登记了 / 纯文字消息不登记"）。 */
    public int indexSize() { return idx.size(); }

    /** 进去过几轮（内存计数，与 kv 的 {@code turns} 同源）。 */
    public long turnCount() { return turns.get(); }

    /** 真调过几次模型（内存计数）。 */
    public long lookCount() { return looks.get(); }

    /** 丢弃次数（预算 / 队满）。 */
    public long dropCount() { return drops.get(); }

    /** 会话尾巴里有几条消息（第一条永远是 brief 之外的对话轮）。 */
    public int historySize() {
        synchronized (history) {
            return history.size();
        }
    }

    /** 清掉 60 秒抑制（只有探针用；线上没有调用点）。 */
    public void clearTransient() { cooldown.clear(); }

    // ================================================================ 配置（prompt.md 是唯一配置源）

    /** 技能自己的配置与文案（每次调用重读 {@code prompt.md}；缺项 = 那一项不生效）。 */
    public static final class Cfg {
        public long cacheMs = 168L * 3600000L;
        public long perMin = 12L;
        public long perDay = 300L;
        public long idleExitMs = 120000L;
        public long maxBytes = 4194304L;
        public long waitMs = 60000L;
        /** 身份索引有效期（{@code 索引TTL秒}；默认 1800 = 30 分钟 = 与基板 {@code Inbound} 同寿）。 */
        public long indexTtlMs = INDEX_TTL_MS;
        public int windowTurns = 8;
        public String model = "";
        public String ask = "";
        /**
         * 图片审查词表两枚（{@code 禁图-涉政} / {@code 禁图-黄赌毒}，逗号分隔的**原文**）。
         * <p><b>一个词都不在 Java 里</b>：这里只是从 prompt.md 的「配置」一节读到的串；
         * 缺行 / 整份读不到 ⇒ 空串 ⇒ {@link #imgScreen} 判"读不到词表" ⇒ 放行 + 一行 warn（fail-open）。</p>
         */
        public String imgPolitics = "";
        public String imgVice = "";
        public Map<String, String> texts = new LinkedHashMap<String, String>();

        /** 读技能自己目录里的 {@code prompt.md}（读不到 ⇒ 全默认）。 */
        public static Cfg load(Host h) {
            Cfg c = new Cfg();
            String text = "";
            try {
                File dir = h == null ? null : h.skillDir();
                if (dir != null) text = Fs.read(new File(dir, "prompt.md"), "");
            } catch (Throwable ignored) {
            }
            if (Str.blank(text)) return c;
            Map<String, String> kv = pairs(section(text, "配置"));
            c.texts = pairs(section(text, "文案"));
            c.ask = section(text, "提示词");
            Map<String, String> askKv = pairs(c.ask);
            if (Str.has(askKv.get("默认问题"))) c.ask = Str.trim(askKv.get("默认问题"));
            c.cacheMs = (long) (dec(kv.get("缓存小时"), 168.0D) * 3600_000.0D);
            c.perMin = atLeast(num(kv.get("每分钟上限"), 12L), 0L, 100000L);
            c.perDay = atLeast(num(kv.get("每日上限"), 300L), 0L, 10000000L);
            c.idleExitMs = atLeast(num(kv.get("空闲退出秒"), 120L), 1L, 86400L) * 1000L;
            c.maxBytes = atLeast(num(kv.get("最大图片字节"), 4194304L), 1024L, 64L * 1024L * 1024L);
            long wait = atLeast(num(kv.get("等待秒"), 60L), 1L, 600L);
            c.waitMs = wait * 1000L;
            long ttlSec = atLeast(num(kv.get("索引TTL秒"), INDEX_TTL_MS / 1000L), 1L, 7L * 86400L);
            c.indexTtlMs = ttlSec * 1000L;
            c.windowTurns = (int) atLeast(num(kv.get("对话窗口轮"), 8L), 0L, 64L);
            c.model = Str.trim(kv.get("视觉模型"));
            // 图片审查词表（原文读进来；**不在 Java 里落任何一个词**）——
            // 缺行 ⇒ 空串 ⇒ imgScreen 判"读不到词表" ⇒ 放行 + 一行 warn（fail-open）。
            c.imgPolitics = Str.trim(kv.get(IMG_KEY_POLITICS));
            c.imgVice = Str.trim(kv.get(IMG_KEY_VICE));
            return c;
        }

        /** 一条文案（缺项用结构性兜底）。 */
        public String msg(String key, String fallback, String... kv) {
            String tpl = texts == null ? null : texts.get(key);
            return fill(Str.has(tpl) ? tpl : fallback, kv);
        }
    }

    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

    /** {@code ## 名字} 一节的内容（到下一个 {@code ## } 为止）。 */
    static String section(String text, String name) {
        if (Str.blank(text) || Str.blank(name)) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String head = "## " + name.trim();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(head)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().startsWith("## ")) break;
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code 键: 值} 一行一条（{@code #} 开头是注释）。 */
    static Map<String, String> pairs(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (Str.blank(body)) return m;
        for (String raw : body.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i < 0) i = line.indexOf('：');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static long atLeast(long v, long min, long max) {
        long x = v < min ? min : v;
        return x > max ? max : x;
    }
}
