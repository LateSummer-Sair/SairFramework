import com.google.gson.JsonObject;

import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Hooked;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 辅助识图的入口类（工具 {@code look} + 钩子 {@code on_message}）：<b>只做入口该做的事</b>，
 * 识图本体在 {@link VisionAgent}。
 *
 * <ol>
 *   <li><b>参数</b>：{@code {id, seg, ask, refresh}}。{@code id} = 消息 id 的<b>十进制串</b>（必填），
 *       {@code seg} = 这条消息里第几个<b>图片段</b>（0 起，缺省 0），{@code ask} = 要它看图里的什么，
 *       {@code refresh} = 忽略账本重新识别一次。<b>不接受 URL / 路径 / NapCat 的 file 值</b> ——
 *       传 URL 一律拒（回执逐字：{@code [look] ok=0 只接受消息身份（id/seg），不接受 URL}）。</li>
 *   <li><b>权限</b>：这是一个"对外执行"的工具 ⇒ 判 T 类 {@code Res.tool("look")} 的 {@code X} 位
 *       （{@code ALLUSER} 的 T 类默认是 {@code NONE} ⇒ 非主人用不了；要放行只能由账本开口）。</li>
 *   <li><b>回执形状</b>：成功一律 {@link sair.v4.qq.MediaRender#machine}</b>（{@code [识图·<模型>] <描述>}，
 *       自己绝不拼方括号标签）；失败/拒答首行一律 {@code [look] ok=0 <如实原因>}
 *       （{@code agent\Loop.java} 的连续失败闸门靠这个结构标记计数）。</li>
 *   <li><b>钩子 {@code on_message}</b>：入站那一刻把"图像身份 → URL/file"登记进进程内身份索引
 *       （契约阶梯① 的真实来源，见 {@link VisionAgent#noteInbound(JsonObject)}）。
 *       <b>只碰内存</b>：零 I/O、零网络、零 kv 写、不改 payload、绝不抛 —— 它在<b>入站同步路径</b>上。</li>
 * </ol>
 */
public class LookTool implements Skill, Hooked {

    /** 唯一的 op。 */
    private static final String OP = "look";

    /**
     * 钩子入口：只接 {@code on_message}，其余钩子名一律忽略（本技能只声明了这一个）。
     * <p>转发给 {@link VisionAgent#noteInbound(JsonObject)} —— 那里做了内存登记 + amortized O(1) 剪枝；
     * 这里不做任何别的事（不说话、不注入、不改 {@code _handled}/{@code _reply}）。</p>
     */
    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_message".equals(Str.trim(hook))) return;
        VisionAgent.noteInbound(payload);
    }

    @Override
    public Object call(Args a, Host h) {
        if (h == null) return VisionAgent.FB_HOST_NULL;
        VisionAgent.Cfg c = VisionAgent.Cfg.load(h);
        try {
            VisionAgent.OUT = h.out();                          // 日志出口（技能自己没有 Out 字段）
            String op = Str.lower(Str.trim(a.str("op")));
            if (!op.isEmpty() && !OP.equals(op)) {
                return c.msg("不支持的op", VisionAgent.FB_OP, "{op}", op);
            }
            String id = Str.trim(a.str("id"));
            if (id.isEmpty()) return c.msg("缺身份", VisionAgent.FB_NO_ID);
            if (isUrl(id)) return c.msg("不接受URL", VisionAgent.FB_URL);
            if (!digits(id)) return c.msg("身份非数字", VisionAgent.FB_NOT_NUM, "{id}", id);
            // seg 口径（跨线接口对账，别隐式混用）：技能 seg = 这条消息里第几个 image 段（**0 起**）；
            // 基板 qq\MediaAttach.java:71 的 Skip.ord = 第几张图（**1 起**，注释逐字"它是第几张图（1 起）"）
            // ⇒ 要跟基板对账必须**显式**写 ord = seg + 1，不许把 0 起的 seg 直接当 1 起的 ord 用。
            int seg = a.i("seg", 0);
            if (seg < 0 || seg > VisionAgent.SEG_MAX) return c.msg("段序非法", VisionAgent.FB_SEG);
            String ask = Str.trim(a.str("ask"));
            boolean refresh = a.b("refresh", false);

            // 出网取图 + 调模型 = 执行一个工具 ⇒ 判这把工具的 X 位（T 类；ALLUSER 默认无位）
            String deny = h.need(sair.v4.auth.Res.tool(OP), 'X');
            if (deny != null) return deny;

            return VisionAgent.get().lookOne(h, id, seg, ask, refresh, c);
        } catch (Throwable t) {
            return c.msg("异常", VisionAgent.FB_ERROR, "{error}", String.valueOf(t));
        }
    }

    /** 长得像 URL 的一律拒（这一条是冻结契约：只收消息身份）。 */
    static boolean isUrl(String s) {
        String v = Str.lower(Str.trim(s));
        if (v.isEmpty()) return false;
        if (v.indexOf("://") >= 0) return true;
        return v.startsWith("http:") || v.startsWith("https:") || v.startsWith("data:")
                || v.startsWith("file:") || v.startsWith("//");
    }

    /** 十进制串（消息 id 的形态；别的形态一律拒，绝不拿去猜）。 */
    static boolean digits(String s) {
        String v = Str.trim(s);
        if (v.isEmpty() || v.length() > 20) return false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (Throwable t) {
            return false;
        }
        return n > 0L;
    }
}
