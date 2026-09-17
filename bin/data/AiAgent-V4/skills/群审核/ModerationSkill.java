import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.auth.Caller;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;

/**
 * 群审核的<b>运行期配置口</b>（工具 {@code moderation}，op {@code config}）：读/写"按群配置"。
 *
 * <p>这个类同时是「群审核」技能的<b>入口类</b>（实现 {@link Skill} + {@link Hooked}）：
 * 钩子逻辑仍在 {@link ModerationHook}，这里只做转发 —— 这样一个技能文件夹里既能挂 {@code on_message}
 * 钩子，又能给模型一个改配置的工具（V3 用能力 {@code 群审核.moderation.config} 达到同样效果）。</p>
 *
 * <h3>权限（ACL 资源模型）</h3>
 * <ul>
 *   <li>读配置只判 {@code kv} 的读位（C:{@code R}）—— 默认位就是 {@code R}，所以非主人也读得到；</li>
 *   <li><b>任何写操作</b>（on / words / mute / roles / users / warn / note / clear）先过<b>归属 / 角色硬判</b>
 *       （{@link ModCfg#writeDeny}）：主人（{@code MASTER}）与她自己的自主行为（{@code SYSTEM}）照常；
 *       普通用户（{@code ALLUSER}）只允许改<b>自己所在群</b>且自己在该群是群主/管理员的配置，
 *       跨群写入一律拒（拒绝文案以 {@code [权限阻断]} 开头）。
 *       <b>这一判不是权限位</b>：C 类（kv）对非主人只有 {@code R}（写被位拦下），
 *       {@code h.needDb("kv", 'W')} 拦得住普通用户的写 —— 旧档位里"默认只有 {@code R} ⇒ 非主人改不动策略"
 *       那条防线回来了（归属硬判仍然保留）；而这里的群号还是调用方给的参数，只能靠显式判定拦住。
 *       写位判定仍保留在原处，它只回答"库能不能写"，归属问题由这道硬判回答。</li>
 * </ul>
 *
 * <p>{@code op=config} 的所有话术都读自同目录 {@code prompt.md}（缺键 = 明确报缺键，没有兜底）。</p>
 */
public class ModerationSkill implements Skill, Hooked {

    private static final String TOOL = "moderation";

    /** 钩子逻辑（同一个技能文件夹里的另一个类）。 */
    private final ModerationHook hook = new ModerationHook();

    /** 审核配置落在通用 kv 表（键前缀 {@code modcfg:}，C 类运行时数据）—— 资源判定用的库名。 */
    private static final String LIB_KV = "kv";

    // ================================================================ 钩子转发

    @Override
    public void on(String hookName, JsonObject payload, Host h) {
        hook.on(hookName, payload, h);
    }

    // ================================================================ 工具

    @Override
    public Object call(Args a, Host h) {
        try {
            if (h == null) return "[" + TOOL + "] host=null";
            String op = Str.lower(Str.trim(a.str("op")));
            if (op.isEmpty()) op = "config";
            if ("config".equals(op)) return config(a, h);
            return ModCfg.text(h, "不支持op", "op", op);
        } catch (Throwable t) {
            return ModCfg.text(h, "异常", "error", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- op=config

    private static String config(Args a, Host h) {
        Caller caller = h.caller();
        long group = a.l("group", 0L);
        if (group <= 0L && caller != null && caller.groupId() > 0L) group = caller.groupId();

        List<String> fields = requested(a);
        boolean clear = a.b("clear", false);
        boolean write = !fields.isEmpty() || clear;

        if (write) {
            // 归属 / 角色硬判（隔离手段，不是权限位）：写配置必须是主人，或"目标群就是我说话的群
            // 且我是这个群的群主/管理员"。跨群写入一律拒 —— 群号是调用方给的参数，
            // 没有这一判任何人都能改任意群的群规（详见 ModCfg.writeDeny）。
            String owner = ModCfg.writeDeny(caller, group);
            if (owner != null) return owner;
            // 资源判定（ACL）：改审核配置 = 写通用 kv 表（键 modcfg:<群号>，C 类）→ 判写位。
            // C 类对非主人只有 R（写被位拦下），所以这一判也拦住非主人的写；
            // "谁能改配置"由上面那条归属硬判回答。
            String deny = h.needDb(LIB_KV, 'W');
            if (deny != null) return deny;
            if (group <= 0L) return ModCfg.text(h, "缺群号");
        }
        if (group <= 0L) {
            // 读：没给群号也不在群里 → 回全局默认（来源 prompt.md）
            return render(h, ModCfg.global(h));
        }

        if (clear) {
            int n = ModCfg.clear(h.store(), group);
            log(h, "已清除 " + ModCfg.PREFIX + group + "（" + n + " 条）");
            return ModCfg.text(h, "配置已清除", "group", String.valueOf(group));
        }
        if (write) {
            JsonObject patch = patch(a, fields);
            if (patch == null || patch.entrySet().isEmpty()) return ModCfg.text(h, "配置没改");
            if (!ModCfg.write(h.store(), group, patch)) {
                return ModCfg.text(h, "配置写入失败", "group", String.valueOf(group));
            }
            ModCfg.stamp(h.store(), group, caller == null ? 0L : caller.qq());
            log(h, "已更新 " + ModCfg.PREFIX + group + "：" + Str.join(fields, ","));
            return ModCfg.text(h, "配置已更新", "group", String.valueOf(group),
                    "fields", Str.join(fields, ","))
                    + "\n" + render(h, ModCfg.effective(h, group));
        }
        return render(h, ModCfg.effective(h, group));
    }

    /** 本次调用想改哪些键（结构性键名，不是文案）。 */
    private static List<String> requested(Args a) {
        List<String> out = new ArrayList<String>();
        if (a.has("on")) out.add("on");
        if (a.has("words")) out.add("words");
        if (a.has("mute")) out.add("mute");
        if (a.has("roles")) out.add("roles");
        if (a.has("users")) out.add("users");
        if (a.has("warn")) out.add("warn");
        if (a.has("note")) out.add("note");
        return out;
    }

    /** 按请求的键组 patch（复用 ModCfg 的口径：mute < 60 抬到 60）。 */
    private static JsonObject patch(Args a, List<String> fields) {
        JsonObject o = new JsonObject();
        o.addProperty("v", ModCfg.SCHEMA);
        for (String f : fields) {
            if ("on".equals(f)) o.addProperty("on", a.b("on", false));
            else if ("words".equals(f)) o.add("words", list(a, "words"));
            else if ("mute".equals(f)) o.addProperty("mute", Math.max(60, a.i("mute", 600)));
            else if ("roles".equals(f)) o.add("roles", list(a, "roles"));
            else if ("users".equals(f)) o.add("users", list(a, "users"));
            else if ("warn".equals(f)) o.addProperty("warn", a.str("warn"));
            else if ("note".equals(f)) o.addProperty("note", a.str("note"));
        }
        return o;
    }

    /** 字符串参数：数组或逗号分隔都能收。 */
    private static JsonArray list(Args a, String key) {
        JsonArray arr = new JsonArray();
        List<String> strs = a.strs(key);
        if (strs != null && !strs.isEmpty()) {
            for (String s : strs) if (Str.has(s)) arr.add(Str.trim(s));
            return arr;
        }
        for (String s : ModCfg.split(a.str(key))) arr.add(s);
        return arr;
    }

    /** 渲染一份配置（读操作与写操作后都用它）。{@code ModCfg.text} 的占位符名不带花括号。 */
    private static String render(Host h, ModCfg.Cfg c) {
        StringBuilder sb = new StringBuilder();
        sb.append(ModCfg.text(h, "配置头", "group", c.groupId > 0L ? String.valueOf(c.groupId) : "-",
                "source", c.source)).append('\n');
        sb.append(ModCfg.text(h, "配置-开关", "on", c.enabled ? "true" : "false")).append('\n');
        sb.append(ModCfg.text(h, "配置-违规词", "count", String.valueOf(c.words.size()),
                "words", c.words.isEmpty() ? ModCfg.text(h, "配置-无") : ModCfg.joinList(c.words))).append('\n');
        sb.append(ModCfg.text(h, "配置-禁言", "mute", String.valueOf(c.mute))).append('\n');
        sb.append(ModCfg.text(h, "配置-豁免角色", "roles",
                c.skipRoles.isEmpty() ? ModCfg.text(h, "配置-无") : ModCfg.joinList(c.skipRoles))).append('\n');
        sb.append(ModCfg.text(h, "配置-豁免QQ", "users",
                c.users.isEmpty() ? ModCfg.text(h, "配置-无") : ModCfg.join(c.users))).append('\n');
        sb.append(ModCfg.text(h, "配置-跳过群", "skip", ModCfg.join(c.skipGroups))).append('\n');
        sb.append(ModCfg.text(h, "配置-警告文案", "warn",
                Str.has(c.warn) ? c.warn : ModCfg.text(h, "配置-无")));
        if (Str.has(c.note)) {
            sb.append('\n').append(ModCfg.text(h, "配置-规则说明", "note", c.note));
        }
        return sb.toString().trim();
    }

    // ================================================================ 小工具

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.warn("[" + ModCfg.NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** 给诊断输出用：当前所有按群配置的键（不含值）。 */
    static List<String> keys(Host h) {
        List<String> out = new ArrayList<String>();
        for (JsonObject row : ModCfg.all(h == null ? null : h.store(), 200)) {
            out.add(J.s(row, "key", ""));
        }
        return out;
    }
}
