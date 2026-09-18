import java.util.List;
import java.util.Map;

import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;

/**
 * 「点名引用」的<b>教学段</b>（稳定段）：告诉她"@ 谁、引用哪条由你自己决定"，以及怎么写。
 *
 * <p>为什么用 {@link ContextProvider} 而不是写进人设：这条规则是<b>本技能的能力</b>，
 * 技能删掉就该一起消失（人设里留一句就变成了删不掉的残留）；而且 {@code stable()==true}
 * 的段排在同一条系统提示词里<b>构成可复用前缀</b>，逐字不变 ⇒ 走前缀缓存，成本几乎为零。</p>
 *
 * <p>文案全部外挂在 {@code prompt.md}（键 {@code 教学标题} / {@code 教学1..4}），
 * Java 里只留"文件读不到"时的兜底默认值，与出厂值一一对应。</p>
 */
public class AtPickTeach implements ContextProvider {

    private static final String DEF_TITLE = "【点名与引用】@ 谁、引用哪条，你自己定（技能只照你写的执行）";
    private static final String DEF_1 = "想 @ 谁：在回复里写 <at qq=\"他的QQ\"/>（QQ 见下一条清单），放开头或句中随你。";
    private static final String DEF_2 = "想引用哪条：写 <quote id=\"那条的id\"/>（id 见下一条清单），放在你要接的那句话前面。";
    private static final String DEF_3 = "都不想：什么都不写，发出去的就是纯正文 —— 这是默认，不必刻意补 @。";
    private static final String DEF_4 = "分寸：别人说过「别 @ 我」就别再 @ 他；引用只引你真正在回的那条；QQ 与 id 只能从清单里抄，不许自己编。";

    @Override
    public boolean stable() {
        return true;
    }

    /** 顺序取小 = 预算不够时最后被丢（与清单同一取向：这两段是她点名/引用的唯一依据）。 */
    @Override
    public int order() {
        return 6;
    }

    @Override
    public int maxChars() {
        return 1000;
    }

    @Override
    public boolean appliesTo(BuildContext b) {
        if (b == null) return false;
        return AtPickCfg.flag(AtPickCfg.of(b.host()), "开关", true);
    }

    @Override
    public String build(BuildContext b) {
        try {
            Map<String, List<String>> c = AtPickCfg.of(b == null ? null : b.host());
            StringBuilder sb = new StringBuilder();
            add(sb, AtPickCfg.one(c, "教学标题", DEF_TITLE));
            add(sb, AtPickCfg.one(c, "教学1", DEF_1));
            add(sb, AtPickCfg.one(c, "教学2", DEF_2));
            add(sb, AtPickCfg.one(c, "教学3", DEF_3));
            add(sb, AtPickCfg.one(c, "教学4", DEF_4));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void add(StringBuilder sb, String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
    }
}
