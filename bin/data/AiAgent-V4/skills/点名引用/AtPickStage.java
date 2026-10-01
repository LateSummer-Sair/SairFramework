import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.Str;

/**
 * 「点名引用」的<b>执行面</b>：把她自己写的标记换成 OneBot 的 CQ 码。
 *
 * <p>她的写法（两个标记都在基板 {@code qq\MarkerTags} 白名单里 —— 万一这里漏了，
 * 基板也会在发送前剥掉，用户永远看不到裸标签）：</p>
 * <ul>
 *   <li>{@code <at qq="123"/>} ⇒ {@code [CQ:at,qq=123]}</li>
 *   <li>{@code <quote id="456"/>} / {@code <reply id="456"/>} ⇒ {@code [CQ:reply,id=456]}</li>
 * </ul>
 *
 * <p><b>本类不做任何判断</b>：不看谁是主人、不看回复多长、不看时间差、不记住"上一轮是谁"。
 * 她没写标记 ⇒ 这里返回 {@code null}（一个字节都不改）；她写了 ⇒ 就地换成 CQ 码
 * （位置由她定：写在开头就是开头，写在句中就是句中）。这正是与旧「@提问者」的区别 ——
 * 旧的是插件按固定规则替她 @，这是<b>她决定、插件照做</b>。</p>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>号码必须是十进制数字且 &gt; 0：不合法的标记直接<b>丢掉</b>（宁可没有前缀，也不发一个畸形 CQ 码）；</li>
 *   <li>她若自己写了同号的 CQ 码，标记只被丢掉，<b>不重复加</b>；</li>
 *   <li>私聊里 {@code at} 无意义（受 {@code 私聊不@} 管，默认丢），引用照做；</li>
 *   <li>成对写法（{@code <at qq="1">名字</at>} / {@code <quote id="1">原文</quote>}）整段吃掉，
 *       不留内层文字，避免同一句话出现两遍；</li>
 *   <li>取值容忍写法差异：{@code id="5"} / {@code id='5'} / {@code id=5} / {@code id=5/}（自闭合不带空格）
 *       / {@code id=" 5 "}（引号内两侧空白）都认；自闭合的斜杠不算号码的一部分，
 *       带其它属性（{@code <quote id="5" foo="bar"/>}）照旧；标签体是引号感知的（属性值里带 {@code >}
 *       不会把标签截半）；号码判据（十进制且 &gt;0）一个字都不放松，取值里有垃圾
 *       （{@code id="5 /"} / {@code id="5/ >"} / {@code id="5/6"}）⇒ <b>整段丢掉</b>：
 *       既不产码，也不把 {@code "/>} 这类残片当正文漏出去；</li>
 *   <li>异常全吞：出问题就返回 {@code null}（正文保持原样），<b>绝不拖垮一条发送</b>。</li>
 * </ul>
 */
public class AtPickStage implements OutboundStage {

    /** 兜底模板（真正生效的值在 prompt.md）。 */
    private static final String DEF_AT = "[CQ:at,qq={qq}]";
    private static final String DEF_QT = "[CQ:reply,id={id}]";

    /*
     * 取值口径（四条正则共用同一段：("[^"]*"|'[^']*'|[^\s>"'/]*)，标签体一律「引号感知」，
     * 改一处必须四处同改 —— 基板直连路 qq\ChatMarkers 里是同一份拷贝）。
     *
     * 标签体 = (?:[^>"']|"[^"]*"|'[^']*')*：属性值里带 > 不会把标签截半
     * （<quote id="5" title="a > b"/> 是**一个**完整标签，不是到第一个 > 就收口，
     * 所以不会剩下 "/> 之类当正文漏出去）；引号必须成对。
     * 取值段 = "[^"]*"（整段双引号）/ '[^']*'（整段单引号）/ 裸词（不含空白、引号、>、/）；
     * 值后面若还跟着 /，只有当它是自闭合的那一个（/ 紧跟 >）才作数。
     *
     * 认：id="5" / id='5' / id=5 / id=5/（自闭合不带空格）/ id=" 5 " / id=" 5" / id="5 "
     *     （引号内两侧空白）/ id = "5" / 成对 <quote id="5">原文</quote> /
     *     带其它属性 <quote id="5" foo="bar"/> / 大小写混写。
     * 不认（整段 fail-closed：标记跟它自己的字节一起删掉 —— 既不产码，也不把残片当正文漏出去）：
     *     <quotation id="5"/>（标签名要精确）、<quote idx="5"/>（不是 id 这个词）、
     *     正文里孤立的 id=5（没有标记就没有这一步）；号码不合法（空 / 0 / 非十进制 / 超长 /
     *     全角数字）；取值里有垃圾 —— id="5/"、id="5 /"、id="5/6"、id="5 6"、id="5/ >"、id="5>"
     *     都丢掉：宁可没有引用，也不把 "5 /" 截断成 5 那种「看起来对、其实是猜的」号码。
     * <at> 的两条同形，键是 qq。
     */
    /** 成对写法：{@code <at qq="1">名字</at>}（整段吃掉）。 */
    private static final Pattern AT_PAIR = Pattern.compile(
            "<at\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bqq\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*>[\\s\\S]*?</at\\s*>",
            Pattern.CASE_INSENSITIVE);
    /** 自闭合/单标签写法：{@code <at qq="1"/>}。 */
    private static final Pattern AT_ONE = Pattern.compile(
            "<at\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bqq\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*/>",
            Pattern.CASE_INSENSITIVE);
    /** 引用的成对写法：{@code <quote id="1">原文</quote>}（{@code reply} 同名同义）。 */
    private static final Pattern QT_PAIR = Pattern.compile(
            "<(?:quote|reply)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bid\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*>[\\s\\S]*?</(?:quote|reply)\\s*>",
            Pattern.CASE_INSENSITIVE);
    /** 引用的自闭合写法：{@code <quote id="1"/>}。 */
    private static final Pattern QT_ONE = Pattern.compile(
            "<(?:quote|reply)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bid\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*/>",
            Pattern.CASE_INSENSITIVE);

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<String> finish(List<String> parts, Outbound m) {
        try {
            if (parts == null || parts.isEmpty() || m == null) return null;
            Map<String, List<String>> c = AtPickCfg.of(m.host());
            if (!AtPickCfg.flag(c, "开关", true)) return null;

            boolean group = m.group();
            boolean atInPrivate = !AtPickCfg.flag(c, "私聊不@", true);
            String atTpl = AtPickCfg.one(c, "at模板", DEF_AT);
            String qtTpl = AtPickCfg.one(c, "引用模板", DEF_QT);

            List<String> out = new ArrayList<String>(parts);
            boolean changed = false;
            for (int i = 0; i < out.size(); i++) {
                String s = out.get(i);
                if (s == null || s.indexOf('<') < 0) continue;
                String r = s;
                r = swap(r, QT_PAIR, qtTpl, "id");
                r = swap(r, QT_ONE, qtTpl, "id");
                if (group || atInPrivate) {
                    r = swap(r, AT_PAIR, atTpl, "qq");
                    r = swap(r, AT_ONE, atTpl, "qq");
                } else {
                    r = drop(r, AT_PAIR);
                    r = drop(r, AT_ONE);
                }
                if (!r.equals(s)) {
                    out.set(i, r.trim());          // 丢掉标记后可能留下首尾空白（不 trim 会发出一个空开头的段）
                    changed = true;
                }
            }
            return changed ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 小工具 ====================

    /** 把命中的标记换成 CQ 码；号码不合法 ⇒ 丢掉标记；已有同号 CQ 码 ⇒ 只丢标记不重复加。 */
    private static String swap(String text, Pattern p, String tpl, String key) {
        if (text == null || text.isEmpty()) return text;
        Matcher mm = p.matcher(text);
        if (!mm.find()) return text;
        StringBuffer sb = new StringBuffer();
        do {
            String val = num(mm.group(1));
            String rep = "";
            if (val != null) {
                String code = code(tpl, key, val);
                if (!code.isEmpty() && !already(text, key, val)) {
                    rep = code;
                    // 只在"后面紧跟非空白"时补一个空格（CQ 码与前文/后文都不粘连）
                    if (mm.end() < text.length() && !Character.isWhitespace(text.charAt(mm.end()))) rep = rep + " ";
                }
            }
            mm.appendReplacement(sb, Matcher.quoteReplacement(rep));
        } while (mm.find());
        mm.appendTail(sb);
        return sb.toString();
    }

    /** 只丢掉命中的标记（私聊里的 {@code at}）。 */
    private static String drop(String text, Pattern p) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('<') < 0) return text;
        return p.matcher(text).replaceAll("");
    }

    /** 十进制校验：不合法的号码返回 {@code null}（标记丢掉）。 */
    private static String num(String raw) {
        String s = unquote(raw);
        if (s.isEmpty() || s.length() > 20) return null;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        }
        if (s.charAt(0) == '0') return null;
        return s;
    }

    /** 取值段是「整段取值」（可能带成对引号）：剥掉外面那一对引号与两侧空白，再判号码。 */
    private static String unquote(String raw) {
        String s = Str.trim(Str.nz(raw));
        if (s.length() >= 2) {
            char q = s.charAt(0);
            if ((q == '"' || q == '\'') && s.charAt(s.length() - 1) == q) {
                s = Str.trim(s.substring(1, s.length() - 1));
            }
        }
        return s;
    }

    /** 模板 → CQ 码（占位符字面替换）。 */
    private static String code(String tpl, String key, String val) {
        String s = tpl == null ? "" : tpl;
        if (Str.blank(s)) return "";
        return s.replace("{" + key + "}", val).trim();
    }

    /** 正文里是不是已经有同一个号的 CQ 码（她自己写的，避免重复）。 */
    private static boolean already(String text, String key, String val) {
        try {
            String head;
            String tail;
            if ("qq".equals(key)) {
                head = "\\[CQ:at\\s*,\\s*qq\\s*=\\s*[\"']?";
                tail = "[\"']?\\s*(?:,[^\\]]*)?\\]";
            } else {
                head = "\\[CQ:reply\\s*,\\s*id\\s*=\\s*[\"']?";
                tail = "[\"']?\\s*(?:,[^\\]]*)?\\]";
            }
            return Pattern.compile(head + Pattern.quote(val) + tail, Pattern.CASE_INSENSITIVE)
                    .matcher(text).find();
        } catch (Throwable t) {
            return false;
        }
    }
}
