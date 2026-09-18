import java.util.List;
import java.util.Map;

import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.skill.Host;

/**
 * 「点名引用」的<b>清单段</b>（易变段）：把本群最近几条入站的
 * {@code message_id} 与 QQ 摆到她眼前 —— 她手机上看到的窗口里<b>没有这两个号</b>，
 * 没有清单就只能靠编。
 *
 * <p>只读自己的 kv（一次主键点查），不查任何库、不调任何模型；只对群生效。</p>
 *
 * <p>这一段是"给她做决定用的原料"，<b>不含任何判断</b>：不标"该 @ 谁"，也不标"该引用哪条"。</p>
 */
public class AtPickList implements ContextProvider {

    private static final String DEF_TITLE = "【本群最近消息】（要 @ 谁、引用哪条，你自己定）";
    private static final String DEF_ROW = "- id={id} qq={qq} {name}: {text}";
    private static final String DEF_EMPTY = "【本群最近消息】暂时没有可引用的记录（那就什么都别加，只发正文）。";
    private static final String DEF_TAIL = "上面是按时间排的，最后一条就是刚来的那条。";
    private static final long DEF_KEEP = 10L;
    private static final long DEF_TTL_SEC = 600L;

    @Override
    public boolean stable() {
        return false;
    }

    /**
     * 顺序刻意取小：全局预算 {@code ctxBudgetChars} 不够时是<b>从 order 最大的开始整段丢</b>，
     * 而这一段的清单是她 @ 谁 / 引用哪条的唯一依据 —— 被丢了她就只能靠编。所以排在最前，最后才轮到它。
     */
    @Override
    public int order() {
        return 5;
    }

    @Override
    public int maxChars() {
        return 1200;
    }

    @Override
    public boolean appliesTo(BuildContext b) {
        if (b == null || !b.group() || b.groupId() <= 0L) return false;
        return AtPickCfg.flag(AtPickCfg.of(b.host()), "开关", true);
    }

    @Override
    public String build(BuildContext b) {
        try {
            Host h = b == null ? null : b.host();
            if (h == null || b.groupId() <= 0L) return "";
            Map<String, List<String>> c = AtPickCfg.of(h);
            long now = System.currentTimeMillis();
            long ttl = AtPickCfg.num(c, "记录有效期秒", DEF_TTL_SEC);
            if (ttl <= 0L) ttl = DEF_TTL_SEC;
            List<AtPickMemo.Rec> ring = AtPickMemo.ring(h, b.groupId(), now, ttl * 1000L);
            if (ring.isEmpty()) return AtPickCfg.one(c, "清单空", DEF_EMPTY);

            String row = AtPickCfg.one(c, "清单行", DEF_ROW);
            StringBuilder sb = new StringBuilder();
            sb.append(AtPickCfg.one(c, "清单标题", DEF_TITLE));
            int from = Math.max(0, ring.size() - (int) Math.max(1L, Math.min(30L, AtPickCfg.num(c, "清单条数", DEF_KEEP))));
            for (int i = from; i < ring.size(); i++) {
                AtPickMemo.Rec r = ring.get(i);
                if (r == null) continue;
                sb.append('\n').append(fill(row, r));
            }
            String tail = AtPickCfg.one(c, "清单尾", DEF_TAIL);
            if (tail != null && !tail.trim().isEmpty()) sb.append('\n').append(tail.trim());
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 占位符字面替换（{@code {id}} / {@code {qq}} / {@code {name}} / {@code {text}} / {@code {ago}}）。 */
    private static String fill(String tpl, AtPickMemo.Rec r) {
        String s = tpl == null ? DEF_ROW : tpl;
        long ago = Math.max(0L, (System.currentTimeMillis() - r.ts) / 1000L);
        s = s.replace("{id}", String.valueOf(r.id))
             .replace("{qq}", String.valueOf(r.qq))
             .replace("{name}", r.name == null ? "" : r.name)
             .replace("{text}", r.text == null ? "" : r.text)
             .replace("{ago}", String.valueOf(ago));
        return s.trim();
    }
}
