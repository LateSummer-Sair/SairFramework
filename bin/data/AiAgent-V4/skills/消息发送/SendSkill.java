import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 消息发送（工具 {@code send}）：V3 那批"发消息 / 发文件 / 发语音 / 转发 / 转告"碎工具的统一出口。
 *
 * <p>八个 op：{@code group}（群发可 @）、{@code private}、{@code file}（发到当前会话）、
 * {@code fileto}（发给指定人）、{@code record}（语音）、{@code forward}（历史消息 → 带出处的文本 → 发）、
 * {@code relay}（转告给某人）、{@code recall}（撤回：显式 id / 最近 N 条自己发的 / 时间窗，逐条撤逐条报）。</p>
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>每个 op 先判自己的 op 再动手</b>：每个动作分支开头判一次 {@code h.need("send.<动作>")}（看技能管控表：
 *       {@code Run} 放行才动手、{@code Ban}/空 = 不可用）；不过就把拒绝原文原样返回。NapCat 没连接一律返回
 *       {@link Api#NOT_CONNECTED}。
 *       读消息（{@code get_msg}/{@code get_forward_msg}）与拉历史是这些动作内部的步骤，不再各判一个 op。</li>
 *   <li><b>未连接不硬发</b>：参数校验与 op 判定过了之后、真正发之前查连接，未连接一律返回 {@link Api#NOT_CONNECTED}。</li>
 *   <li><b>取不回就说清楚</b>：{@code forward} 逐条 {@code get_msg}，失败的条目带上错误串明确列出；
 *       一条都取不回就整条不发。</li>
 * </ol>
 *
 * <p>一切面向模型/用户的文本都在同目录的 {@code prompt.md}（{@code ## 配置} / {@code ## 文案}），
 * 运行期重读；Java 里只有 op 名、动作名、kv/库名、日志前缀与模板缺失时的结构性兜底。</p>
 */
public class SendSkill implements Skill {

    /** 技能名（= 文件夹名 = md 文件名）。 */
    private static final String NAME = "消息发送";
    /** OneBot 动作名（技能不硬编码授权：一律走 {@code h.need("send.<动作>")}）。 */
    private static final String ACT_GROUP = "send_group_msg";
    private static final String ACT_GET_MSG = "get_msg";
    private static final String ACT_FORWARD_MSG = "get_forward_msg";
    private static final String ACT_DELETE = "delete_msg";
    private static final String ACT_HIST_GROUP = "get_group_msg_history";
    private static final String ACT_HIST_PRIVATE = "get_friend_msg_history";
    /** {@code at} 取这个值 = @全体成员。 */
    private static final String AT_ALL = "all";
    /** forward 的结构性硬上限（prompt.md 的 {@code 最多转述条数} 只能比它小）。 */
    private static final int FORWARD_HARD_MAX = 30;
    /** recall 的结构性硬上限（prompt.md 的 {@code 撤回最多条数} 只能比它小）。 */
    private static final int RECALL_HARD_MAX = 100;
    /** recall 拉历史的结构性硬上限（一次最多看 100 条；prompt.md 的 {@code 撤回历史条数} 只能比它小）。 */
    private static final int RECALL_HIST_MAX = 100;
    /** 回执里"跳过的别人消息"最多列几条明细（超过只报条数，避免整屏）。 */
    private static final int RECALL_NOTE_MAX = 10;

    // ================================================================ 入口

    /**
     * 声明本技能的八个 op（技能管控表就是按这些名字放行或封禁的）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("send", "group", "群发一条（可 @ 人 / @全体）");
        ops.add("send", "private", "私聊发一句");
        ops.add("send", "file", "发文件到当前会话");
        ops.add("send", "fileto", "发文件给指定的人");
        ops.add("send", "record", "发语音");
        ops.add("send", "forward", "把历史消息转述出来再发");
        ops.add("send", "relay", "转告一句话给某人");
        ops.add("send", "recall", "撤回她自己发的消息");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        if (h == null) return "[" + NAME + "] host=null";
        try {
            String op = Str.trim(a.str("op")).toLowerCase();
            if ("group".equals(op)) return group(a, h, c);
            if ("private".equals(op)) return privateMsg(a, h, c);
            if ("file".equals(op)) return file(a, h, c);
            if ("fileto".equals(op)) return fileTo(a, h, c);
            if ("record".equals(op)) return record(a, h, c);
            if ("forward".equals(op)) return forward(a, h, c);
            if ("relay".equals(op)) return relay(a, h, c);
            if ("recall".equals(op)) return recall(a, h, c);
            return msg(c, "不支持op", "unsupported_op op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "工具调用异常：" + t);
            return msg(c, "异常", "error={error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ op：group

    /** 群发（可 @ 若干人或 @全体）。 */
    private static String group(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("send.group");
        if (deny != null) return deny;
        String text = a.str("text");
        if (Str.blank(text)) return msg(c, "缺正文", "missing_text");
        long gid = a.l("group", 0L);
        Caller me = h.caller();
        if (gid <= 0L && me != null && me.isGroup()) gid = me.groupId();
        if (gid <= 0L) return msg(c, "缺目标群", "missing_group");

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        List<String> ats = atList(a.str("at"));
        // 出站三道闸在这里补一遍（控制标记 / 工具调用标记 / 纯文字）：带 at 的那一支走原始 call，
        // 绕过了基板 `Api.sendGroupMsg` 里的那道闸 —— 截图事故（2026-09-18）漏出去的正是
        // "她自己写的 <at .../> 标记 + DSML 残片"当正文发了出去。
        String leak = sair.v4.qq.ToolMarkup.rule(text);
        if (leak != null) return msg(c, "正文带工具调用标记，没发", "blocked_markup rule={rule}", "{rule}", leak);
        text = sair.v4.qq.ChatMarkers.clean(text);
        JsonObject r;
        if (ats.isEmpty()) {
            r = h.napcat().sendGroupMsg(gid, text);
        } else {
            JsonObject params = J.obj("group_id", gid, "message", segs(text, ats));
            r = h.napcat().call(ACT_GROUP, params);
        }
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "group", "{error}", detail(c, r));
        }
        String atNames = ats.isEmpty() ? "" : msg(c, "点名", "at={names}", "{names}", Str.join(ats, "、"));
        return msg(c, "群发成功", "sent_group group={group} chars={chars} at={at}",
                "{group}", String.valueOf(gid), "{chars}", String.valueOf(text.length()), "{at}", atNames);
    }

    // ================================================================ op：private

    /** 私聊发一句。 */
    private static String privateMsg(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("send.private");
        if (deny != null) return deny;
        String text = a.str("text");
        if (Str.blank(text)) return msg(c, "缺正文", "missing_text");
        long qq = a.l("qq", 0L);
        Caller me = h.caller();
        if (qq <= 0L && me != null && !me.isConsole() && !me.isGroup()) qq = me.qq();
        if (qq <= 0L) return msg(c, "缺目标人", "missing_qq");

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        JsonObject r = h.napcat().sendPrivateMsg(qq, text);
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "private", "{error}", detail(c, r));
        }
        return msg(c, "私聊成功", "sent_private qq={qq} chars={chars}",
                "{qq}", String.valueOf(qq), "{chars}", String.valueOf(text.length()));
    }

    // ================================================================ op：file / fileto

    /** 把文件发到**当前会话**（群 → 群文件，私聊 → 私聊文件）。 */
    private static String file(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— 群文件与私聊文件是同一个 send.file
        String deny = h.need("send.file");
        if (deny != null) return deny;
        String file = Str.trim(a.str("file"));
        if (file.isEmpty()) return msg(c, "缺文件", "missing_file");
        String name = Str.trim(a.str("name"));
        Caller me = h.caller();
        boolean group = me != null && me.toGroup();
        long id = group ? me.groupId() : (me == null || me.isConsole() ? 0L : me.qq());
        if (id <= 0L) return msg(c, "缺会话", "no_session");
        // >>> W15D1-A4
        // ★ 甲方 2026-09-26 新令（A4）：东西只发**当前会话**（群里=群文件、私聊=她正在说话的那个人的私聊），
        //   **不许**把一份东西转投到**另一个人的私聊**去 —— 理由不是隐私，是"别去打扰别人"。
        //   `file` 按定义就只发当前会话（`消息发送.md` 第 223 行："file 是唯一例外 —— 它按定义只发当前会话，
        //   要发给指定的人用 fileto"），所以参数里点名了**别的**群 / **别的**人 = 与当前会话不符
        //   ⇒ 当场拒、一个字不发（放在 op 判定之后，这一条是落点规则，不动 `send.file` 那一判）。
        //   一致（就是当前会话）或压根没给 group/qq ⇒ 照旧走下面那条路，行为逐字不变。
        String off = offSession(a, c, group, id);
        if (off != null) return off;
        // <<< W15D1-A4

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        // ★ 2026-09-26（W7/批 13）：走基板的"以她自己身份发文件"出口（`Host.sayFile`）。
        //   为什么必须走：`send.file` 对 ALLUSER 是 Ban，好感度档位闸（favor >= favorFileMin）
        //   会把它按档放行 —— 可内层平台动作 `upload_group_file` 同样对 ALLUSER 是 Ban，
        //   那一跳会按"当轮调用者 = 群友"判死（与批 10 的 `send.record` / `sayMedia` 同一缺陷、
        //   同一改法）。出口把**内层动作**改按 SYSTEM（她本人恒全权）判，并把 File 域的路径判定
        //   显式按**触发者**过一遍（出口内那一步不许省，见 `Host.sayFile` 的 javadoc）。
        //   技能 op（上面那一判 `send.file`）仍是唯一授权闸门，本口一个字都不放宽它。
        //   落点不是当前会话 / 档位不够 / 没绑定调用者 / NapCat 面没装配 ⇒ 出口返回 null
        //   ⇒ 按改前那条路原样发（行为逐字一致），并打同一形状的回退事实行（`via=old`）。
        // 交给平台的 file 值：结构性默认 = 入参原样（这就是本文件历史上那条路，改前逐字节如此）。
        String source = file;
        // >>> W15D1-A1
        // ★ 2026-09-26（W15/批 15，甲方裁决 A1）：**同族 API 口径不一致** —— 同一个相对路径
        //   `files/gm-accept.txt`，`file op=read/list` 认（FileSkill 按数据根解析），`send op=file/fileto`
        //   却把原样字符串交给平台。真机逐字证据（2026-09-26 18:16:47，已部署批 13/14，群 121873503）：
        //   18:16:45 `file {"op":"read","path":"files/gm-accept.txt"}` → `[tool] file … ok=1`，
        //   两秒后 `send {"op":"file","file":"files/gm-accept.txt"}` → `upload_group_file` →
        //   `{"status":"failed","retcode":1200,…"识别URL失败, uri= files/gm-accept.txt"}`；
        //   她又白绕了 ~5 轮（list/config/tools 乱试 + 两次猜绝对路径）才放弃，群里没拿到文件也没有解释。
        //   口径（GM 裁决）：绝对路径 / URL 形态（http:// https:// base64:// file:…）**一个字节都不动**；
        //   其余按 **FileSkill 那一套同一个基**（`h.conf().root()`）与**同一套归一化**（getCanonicalFile
        //   + toRealPath 解 junction）解析；基/配置拿不到或解析不了 ⇒ 保持旧行为（原样字符串），绝不猜。
        //   注意：技能目录**不是 classpath**（`SkCompile`：每个文件夹单独编译进内存），所以这里没法引用
        //   `FileSkill.resolve` —— 基板里也没有共享的路径解析口（`Fs` 没有），只能照抄同一套规则（见 sendPath）。
        source = sendPath(h, file);
        // <<< W15D1-A1
        JsonObject r = h.sayFile("send.file", group, id, source, name);
        if (r == null) {
            r = group ? h.napcat().sendGroupFile(id, source, name) : h.napcat().sendPrivateFile(id, source, name);
            log(h, "say file via=old ok=" + (Api.ok(r) ? 1 : 0)
                    + " by=" + (me == null ? "?" : me.kind() + ":" + me.qq())
                    + " target=" + targetLabel(c, group, id)
                    + (Api.ok(r) ? "" : " error=" + detail(c, r)));
        }
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "file", "{error}", detail(c, r));
        }
        return msg(c, "文件成功", "sent_file name={name} target={target}",
                "{name}", file, "{target}", targetLabel(c, group, id));
    }

    /** 把文件发给**指定的人**（私聊文件）。 */
    private static String fileTo(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("send.fileto");
        if (deny != null) return deny;
        // >>> W15D1-A4
        // ★ 甲方 2026-09-26 新令（A4）：`fileto` 天生就是"**跨**私聊转投"，正是这条令要收的东西 ——
        //   东西只允许发在群文件与（她正在说话的）私聊里，不许从一个私聊送到另一个私聊；
        //   理由不是隐私，是**别去打扰别人**。
        //   判据：① 说话的必须是**主人**（不是主人一律拒）；② 主人的**当前会话必须就是他自己的私聊**
        //   （主人在群里发 fileto 也不行 —— 那同样是把东西投到群外某个人的私聊去）。
        //   放在 op 判定之后：这一条是**落点规则**，`h.need("send.fileto")` 仍是本函数第一句、
        //   一个字没放宽也没收紧；无人说话（控制台 / 自主 SYSTEM）那一半不由本技能收口，
        //   由基座出口那条车道负责（这里对"没有 QQ 说话人"的情形**原样放行**，行为不变）。
        String room = filetoRoom(h, c);
        if (room != null) return room;
        // <<< W15D1-A4
        String file = Str.trim(a.str("file"));
        if (file.isEmpty()) return msg(c, "缺文件", "missing_file");
        long qq = a.l("qq", 0L);
        if (qq <= 0L) return msg(c, "缺目标人", "missing_qq");
        String name = Str.trim(a.str("name"));

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        // 交给平台的 file 值：结构性默认 = 入参原样（这就是本文件历史上那条路，改前逐字节如此）。
        String source = file;
        // >>> W15D1-A1
        // ★ 2026-09-26（W15/批 15，甲方裁决 A1）：与上面 `file` 同一缺陷、同一口径 ——
        //   fileto 也把原样字符串交给平台，数据根相对路径同样在 NapCat 上撞 `retcode 1200 / 识别URL失败`
        //   （真机证据与 `file` 那一段同一时刻、同一次工具调用族）。绝对路径 / URL 形态不动，
        //   其余按 FileSkill 那一套同一个基（`h.conf().root()`）+ 同一套归一化解析；解析不了保持旧行为。
        source = sendPath(h, file);
        // <<< W15D1-A1
        JsonObject r = h.napcat().sendPrivateFile(qq, source, name);
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "fileto", "{error}", detail(c, r));
        }
        return msg(c, "文件成功", "sent_file name={name} target={target}",
                "{name}", file, "{target}", targetLabel(c, false, qq));
    }

    // >>> W15D1

    // ================================================================ 发文件的路径归一（结构性，不是权限）

    /**
     * <b>交给平台的 file 值（W15D1-A1）</b>：绝对路径 / URL 形态 → <b>原样返回</b>；
     * 其余（数据根相对路径）→ 按 {@code h.conf().root()} 解析成绝对路径。
     *
     * <p><b>为什么必须解析</b>：同族 API 口径不一致 —— {@code file op=read/list} 认数据根相对路径
     * （{@link sair.v4.skill.Host} 背后那份 FileSkill 的口径），而本技能的 {@code send op=file/fileto}
     * 把原样字符串交给 NapCat，于是同一个 {@code files/gm-accept.txt} 一个通一个撞
     * {@code retcode 1200「识别URL失败」}（2026-09-26 18:16:47 真机逐字证据，见 {@code file()} 里那段）。</p>
     *
     * <p><b>这里只做"相对谁"的归一，不做权限判定</b>：能不能发这个文件由 op 判定（{@code send.file} /
     * {@code send.fileto}）与基板 File 域（{@code Host.sayFile} 内部那句 {@code localFileDeny}）说了算 ——
     * 本方法一个字都不放宽、也不收紧它们。</p>
     *
     * <p><b>绝不猜</b>：拿不到数据根（{@code conf()==null} / {@code root()==null}）或路径规范化不了
     * ⇒ 返回<b>入参原样</b>，行为与改前逐字节一致（旧那条 1200 也照旧，只是不会更糟）。</p>
     */
    private static String sendPath(Host h, String value) {
        String s = Str.trim(value);
        if (s.isEmpty()) return value;
        // ① URL 形态（含 file:）：一个字节都不动 —— 它们不是数据根相对路径，各有各的取文件方式。
        String low = Str.lower(s);
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("base64://") || low.startsWith("file:")) {
            return value;
        }
        // ② 绝对路径：原样（GM 裁决：不许改写）—— 与 FileSkill.resolve 一样以 File.isAbsolute() 为准。
        try {
            if (new File(s).isAbsolute()) return value;
        } catch (Throwable t) {
            return value;
        }
        // ③ 其余 = 数据根相对路径：按 FileSkill 的**同一个基** + **同一套归一化**解析。
        try {
            File base = dataRoot(h);
            if (base == null) return value;                 // 没有数据根：保持旧行为
            File f = resolveUnder(base, s);
            return f == null ? value : f.getPath();         // 解析不了：保持旧行为
        } catch (Throwable t) {
            return value;                                   // 出任何事都退回原样，绝不猜
        }
    }

    /**
     * 数据根 = {@code h.conf().root()}，与 {@code FileSkill.root(Host)} 同一个基（连 {@code real}
     * 那一层都照抄：Windows 上 {@code getCanonicalPath()} 不解析目录联接，只做规范化会让
     * {@code files/联接/…} 静默指到数据根外面）。拿不到返回 {@code null}。
     */
    private static File dataRoot(Host h) {
        if (h == null || h.conf() == null || h.conf().root() == null) return null;
        try {
            File r = realFile(h.conf().root().getCanonicalFile());
            return r == null ? h.conf().root().getCanonicalFile() : r;
        } catch (Throwable t) {
            return null;                                    // 拿不到就"按拿不到办"（调用方返回原样）
        }
    }

    /**
     * 相对 {@code base} 解析 + 两道结构性归一，与 {@code FileSkill.resolve(File, String)} <b>逐条同一套</b>
     * （技能目录不是 classpath，引用不到那个类；基板 {@code kit.Fs} 也没有共享的路径解析口，
     * 所以这里照抄规则而不是"另发明一套"）。
     */
    private static File resolveUnder(File base, String path) {
        String p = Str.trim(path).replace('\\', '/');
        if (p.isEmpty()) p = ".";
        try {
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(base, p);
            File canon = f.getCanonicalFile();      // ① `..` 与符号链接：规范化
            File r = realFile(canon);               // ② junction / 符号链接：解成真实位置
            return r == null ? canon : r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 真实位置：把"最深一层已存在的祖先"用 {@code toRealPath()} 解开（junction 也露出来），
     * 再把还不存在的尾巴拼回去。失败返回 {@code null}（调用方退回规范化路径，不因此拒绝）。
     * 与 {@code FileSkill.real(File)} 同一套（原因同上：没有共享口）。
     */
    private static File realFile(File f) {
        try {
            List<String> tail = new ArrayList<String>();
            File cur = f;
            while (cur != null && !cur.exists()) {
                tail.add(cur.getName());
                cur = cur.getParentFile();
            }
            if (cur == null) return f.getCanonicalFile();
            File r = cur.toPath().toRealPath().toFile();
            for (int i = tail.size() - 1; i >= 0; i--) r = new File(r, tail.get(i));
            return r.getCanonicalFile();
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 落点规则（甲方 2026-09-26 新令 A4）
    /**
     * {@code file} 的落点检查：参数里点名的 {@code group}/{@code qq} 必须就是当前会话；
     * 不符 ⇒ 返回拒文（调用方原样回给模型），一致 / 没给 ⇒ {@code null}（照旧往下走）。
     *
     * <p>文案走本技能既有那套：{@code prompt.md} 的 [文案] 表 + 结构性兜底。
     * {@code 落点不是当前会话} 这个键 GM 还没写进 {@code prompt.md}（那份文件不在本车道手里），
     * 所以现在走的是下面的 ASCII 兜底串；等 GM 补一行中文即自动改口。</p>
     */
    private static String offSession(Args a, Cfg c, boolean group, long id) {
        long g = a.l("group", 0L);
        long q = a.l("qq", 0L);
        boolean wrongGroup = g > 0L && (!group || g != id);
        boolean wrongQq = q > 0L && (group || q != id);
        if (!wrongGroup && !wrongQq) return null;
        return msg(c, "落点不是当前会话", "off_session want={want} now={now}",
                "{want}", wrongGroup ? targetLabel(c, true, g) : targetLabel(c, false, q),
                "{now}", targetLabel(c, group, id));
    }

    /**
     * {@code fileto} 的落点检查：**只有主人的、且当前会话就是主人自己私聊**的那一次才放行；
     * 其余有 QQ 说话人的情形一律拒（甲方 2026-09-26 新令 A4）。
     * 没有 QQ 说话人（控制台 / 自主 SYSTEM / 钩子）⇒ {@code null}，不由本技能收口。
     */
    private static String filetoRoom(Host h, Cfg c) {
        Caller me = h == null ? null : h.caller();
        if (me == null || me.isConsole()) return null;        // 无人说话：基座出口那条车道收口
        long master = h.conf() == null ? 0L : h.conf().masterQQ();
        boolean own = me.kind() == Caller.Kind.MASTER && !me.toGroup() && me.qq() > 0L
                && (master <= 0L || me.qq() == master);
        if (own) return null;
        return msg(c, "只发自己的私聊", "fileto_own_session_only by={by}",
                "{by}", me.kind() + ":" + me.qq());
    }
    // <<< W15D1
    // ================================================================ op：record

    /**
     * 发语音。落点：显式 {@code qq}/{@code group} <b>优先</b>，都没给才回落到当前会话 ——
     * 所以从本地控制台发起时，只要给了收件人就发得出去（不再拿"控制台不是 QQ 会话"挡回去）。
     *
     * <p><b>动作形状以 {@code Api.catalog()} 为准</b>（原文）：
     * {@code send_group_msg}「发送群消息（message 可用 CQ 码字符串或消息段数组）」参数
     * {@code group_id}/{@code message}/{@code auto_escape}；
     * {@code send_private_msg} 同形（参数 {@code user_id}）。<b>目录里没有"发语音"这个独立动作</b>
     * （既没有 {@code send_record}，也没有 {@code send_group_record}/{@code send_private_record}）——
     * 语音就是这两个动作里的一段 {@code record} 消息段。基板已把这条形状封装成
     * {@code Api.sendGroupRecord(groupId, file)} / {@code Api.sendPrivateRecord(userId, file)}
     * （内部 {@code message = [{"type":"record","data":{"file":…}}]}），本地绝对路径的中转改写与
     * 读权限判定由 {@code Api.call} 那一层统一负责，所以这里走封装、不自己拼动作名。</p>
     */
    private static String record(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— 发语音就是 send.record
        String deny = h.need("send.record");
        if (deny != null) return deny;
        String file = Str.trim(a.str("file"));
        if (file.isEmpty()) return msg(c, "缺文件", "missing_file");
        long[] to = recordTarget(a, h);
        boolean group = to[0] > 0L;
        long id = group ? to[0] : to[1];
        if (id <= 0L) return msg(c, "缺收件人", "missing_recipient");

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        String risk = riskStop(h, c);
        if (risk != null) return risk;

        // ★ 2026-09-24（批 10 ⑤）：语音走的是<b>同一个内层平台动作</b> `send_group_msg`（只是消息段是
        //   record）⇒ 与表情包同一缺陷、同一改法：技能 op（上面那一判 `send.record`）仍是唯一授权闸门，
        //   内层动作改按 SYSTEM（她本人恒全权）判。落点不是当前会话时本口返回 null ⇒ 按改前那条路原样发。
        // 交给平台的 file 值：结构性默认 = 入参原样（改前那条路，逐字节如此）。
        String source = file;
        // >>> W15D1-A1b
        // ★ 2026-09-26（W15/批 15 部署后真机验收，23:54，群 121873503，favor 750 的合成身份）：
        //   **A1 漏了 `record` 这一支** —— 同一份 `files/gm-accept.txt`，`send op=file` 已经成功
        //   （她回「发出去了喔，gm-accept.txt 已经落到群里」），可 `send op=record` 还是把原样相对串
        //   交给了平台，真机逐字回执：
        //   `[NapCat] 动作 can_send_record → ok` 之后
        //   `[NapCat] 动作 send_group_msg → {"status":"failed","retcode":1200,"message":"文件处理失败: 识别URL失败, uri= files/gm-accept.txt"}`。
        //   判据与 A1 完全同源：相对路径按数据根解析（**复用同一个 `sendPath` / `resolveUnder`**，
        //   不写第二套规则）；绝对路径 / `http(s)://` / `base64://` / `file:` 逐字节透传；
        //   拿不到基或解析不了 ⇒ 原样（行为与改前逐字节一致）。判定顺序与回执文案一字不动
        //   （`h.need("send.record")` 仍是本函数第一句，失败仍走 `语音失败` 那条模板）。
        source = sendPath(h, file);
        // <<< W15D1-A1b
        JsonObject r = h.sayMedia(group, id, "record", source);
        if (r == null) {
            r = group ? h.napcat().sendGroupRecord(id, source) : h.napcat().sendPrivateRecord(id, source);
            log(h, "say record via=old ok=" + (Api.ok(r) ? 1 : 0)
                    + " target=" + targetLabel(c, group, id)
                    + (Api.ok(r) ? "" : " error=" + detail(c, r)));
        }
        if (!Api.ok(r)) {
            // 失败必须诚实且可诊断：把平台原始 retcode/status/message 原样带回去，并且明说"语音没发出去"
            return msg(c, "语音失败", "record_failed error={error}", "{error}", detail(c, r));
        }
        return msg(c, "语音成功", "sent_record target={target}",
                "{target}", targetLabel(c, group, id));
    }

    /**
     * 语音落点：显式 {@code group} / {@code qq} 优先（两者都给时以 {@code group} 为准），
     * 只有"一个目标都没给"时才回落到当前会话。控制台没有 QQ 会话，但只要给了目标就照发。
     *
     * @return {@code {群号, QQ}}（用不上的那个是 0）
     */
    private static long[] recordTarget(Args a, Host h) {
        long gid = a.l("group", 0L);
        long qq = a.l("qq", 0L);
        if (gid <= 0L && qq <= 0L) {
            Caller me = h.caller();
            if (me != null && !me.isConsole()) {
                if (me.isGroup()) gid = me.groupId();
                else qq = me.qq();
            }
        }
        return new long[] {gid, qq};
    }

    /**
     * 语音风控预检（{@code can_send_record} —— 这个名字在 {@code Api.catalog()} 里，不是猜的）：
     * 只有平台<b>明确说"不能发"</b>时才提前收手（并把平台原始回执带上）；
     * 查不动（动作不支持、返回里没有 {@code yes}）一律返回 {@code null} 继续发 ——
     * 真正的判定永远是发送动作本身的结果，预检只是让"发不出去"这件事有个准确原因。
     */
    private static String riskStop(Host h, Cfg c) {
        try {
            JsonObject r = h.napcat().call("can_send_record", new JsonObject());
            if (!Api.ok(r)) return null;
            JsonObject d = J.sub(r, "data");
            if (d == null || J.get(d, "yes") == null) return null;
            if (J.b(d, "yes", true)) return null;
            return msg(c, "语音风控", "record_denied error={error}", "{error}", detail(c, r));
        } catch (Throwable t) {
            log(h, "can_send_record 预检失败（不影响发送）：" + t);
            return null;
        }
    }

    // ================================================================ op：forward

    /** 历史消息 → 带出处的文本 → 发（落点：group/qq 参数优先，其次当前会话）。 */
    private static String forward(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— 取历史消息与发出去都是这一个 op 内部的步骤
        String deny = h.need("send.forward");
        if (deny != null) return deny;
        List<String> ids = ids(a);
        if (ids.isEmpty()) return msg(c, "缺消息ID", "missing_ids");
        long gid = a.l("group", 0L);
        long qq = a.l("qq", 0L);
        Caller me = h.caller();
        if (gid <= 0L && qq <= 0L && me != null && !me.isConsole()) {
            if (me.isGroup()) gid = me.groupId();
            else qq = me.qq();
        }
        if (gid <= 0L && qq <= 0L) return msg(c, "缺会话", "no_session");
        boolean toGroup = gid > 0L;
        long target = toGroup ? gid : qq;

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        int max = c.max;
        List<String> lines = new ArrayList<String>();
        List<String> bad = new ArrayList<String>();
        int n = 0;
        for (String id : ids) {
            if (n >= max) break;
            n++;
            fetch(h, c, id, lines, bad, max);
        }
        if (lines.isEmpty()) {
            return msg(c, "转述全失败", "forward_failed detail={detail}", "{detail}", Str.join(bad, "；"));
        }
        String head = fill(msg(c, "转述头", "forward_head from={from} count={count}"),
                "{from}", def(Str.trim(a.str("from")), c.fromDef),
                "{count}", String.valueOf(lines.size()));
        StringBuilder full = new StringBuilder();
        String extra = Str.trim(a.str("text"));
        if (Str.has(extra)) full.append(extra).append('\n');
        full.append(head).append('\n').append(Str.join(lines, c.sep));
        if (!bad.isEmpty()) {
            full.append('\n').append(msg(c, "转述部分失败", "forward_partial count={count} detail={detail}",
                    "{count}", String.valueOf(bad.size()), "{detail}", Str.join(bad, "；")));
        }

        String body = full.toString();
        JsonObject r = toGroup ? h.napcat().sendGroupMsg(target, body) : h.napcat().sendPrivateMsg(target, body);
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "forward", "{error}", detail(c, r));
        }
        return msg(c, "转述成功", "forward_sent count={count} target={target}",
                "{count}", String.valueOf(lines.size()), "{target}", targetLabel(c, toGroup, target));
    }

    /** 取一条历史消息并整理成一行；合并转发顺手展开一层。 */
    private static void fetch(Host h, Cfg c, String id, List<String> lines, List<String> bad, int max) {
        JsonObject r = h.napcat().call(ACT_GET_MSG, J.obj("message_id", numOrStr(id)));
        if (!Api.ok(r)) {
            bad.add(badLine(c, id, detail(c, r)));
            return;
        }
        JsonObject d = J.sub(r, "data");
        if (d == null) {
            bad.add(badLine(c, id, msg(c, "未知结果", "unknown_result")));
            return;
        }
        JsonObject sender = J.sub(d, "sender");
        String name = pick(J.s(sender, "card", ""), J.s(sender, "nickname", ""), J.s(sender, "user_id", ""));
        long from = J.l(sender, "user_id", 0L);
        long ms = J.l(d, "time", 0L) * 1000L;
        JsonElement msgEl = J.get(d, "message");
        if (msgEl == null) msgEl = J.get(d, "raw_message");
        lines.add(fill(c.line, "{time}", when(c, ms), "{name}", name,
                "{qq}", String.valueOf(from), "{text}", messageText(c, msgEl)));

        for (String fid : forwardIds(msgEl)) {
            JsonArray nodes = nodes(h, fid);
            if (nodes == null) {
                bad.add(badLine(c, fid, msg(c, "未知结果", "unknown_result")));
                continue;
            }
            int k = 0;
            int added = 0;
            for (JsonElement e : nodes) {
                if (k >= max) break;
                k++;
                String line = nodeLine(c, e);
                if (line == null) continue;
                lines.add(line);
                added++;
            }
            if (k >= max && added > 0) log(h, "合并转发 " + fid + " 超过 " + max + " 条，后面的没展开");
        }
    }

    /** 合并转发节点 → 一行（兼容 {type:node,data:{uin,name,content}} 与 {sender:{},time,content} 两种形状）。 */
    private static String nodeLine(Cfg c, JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject n = e.getAsJsonObject();
        JsonObject d = J.sub(n, "data");
        JsonObject src = d != null ? d : n;
        long qq = J.l(src, "user_id", J.l(src, "uin", 0L));
        String name = pick(J.s(src, "card", ""), J.s(src, "nickname", ""), J.s(src, "name", ""),
                qq > 0L ? String.valueOf(qq) : "");
        long ms = J.l(src, "time", 0L) * 1000L;
        JsonElement body = J.get(src, "message");
        if (body == null) body = J.get(src, "content");
        if (body == null) body = J.get(src, "raw_message");
        return fill(c.nestLine, "{time}", when(c, ms), "{name}", name,
                "{qq}", String.valueOf(qq), "{text}", messageText(c, body));
    }

    /** 拉一次合并转发内容；失败返回 null。 */
    private static JsonArray nodes(Host h, String fid) {
        JsonObject params = J.obj("message_id", numOrStr(fid), "id", numOrStr(fid));
        JsonObject r = h.napcat().call(ACT_FORWARD_MSG, params);
        if (!Api.ok(r)) return null;
        JsonElement d = J.get(r, "data");
        if (d == null) return null;
        if (d.isJsonArray()) return d.getAsJsonArray();
        if (d.isJsonObject()) return J.list(d.getAsJsonObject(), "messages");
        return null;
    }

    // ================================================================ op：relay

    /** 把一句话转告给某人（私聊）。 */
    private static String relay(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("send.relay");
        if (deny != null) return deny;
        long qq = a.l("qq", 0L);
        if (qq <= 0L) return msg(c, "缺目标人", "missing_qq");
        String text = a.str("text");
        if (Str.blank(text)) return msg(c, "缺正文", "missing_text");
        String from = Str.trim(a.str("from"));
        String body = Str.has(from)
                ? msg(c, "转告正文", "relay_body from={from} text={text}", "{from}", from, "{text}", text)
                : msg(c, "转告正文无出处", "relay_body text={text}", "{text}", text);

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        JsonObject r = h.napcat().sendPrivateMsg(qq, body);
        if (!Api.ok(r)) {
            return msg(c, "发送失败", "send_fail op={op} error={error}", "{op}", "relay", "{error}", detail(c, r));
        }
        return msg(c, "转告成功", "relay_sent qq={qq}", "{qq}", String.valueOf(qq));
    }

    // ================================================================ op：recall

    /**
     * 撤回（动作名 {@code delete_msg}，形状以 {@code Api.catalog()} 为准：{@code {message_id}}）。
     *
     * <p>三种范围（<b>至少要一个</b>）：</p>
     * <ol>
     *   <li>{@code message_ids}（或 {@code message_id} 单条）—— 显式点名，先核实发送者再撤；</li>
     *   <li>{@code last} —— 最近 N 条<b>她自己发的</b>（默认 1 条）；</li>
     *   <li>{@code within}（秒，默认 120）/ {@code since} / {@code until}（毫秒时间戳）—— 时间窗。</li>
     * </ol>
     *
     * <p>范围靠<b>拉历史</b>定位：群 {@code get_group_msg_history} / 私聊 {@code get_friend_msg_history}
     * （{@code count} 取 {@code max(撤回历史条数, last*3)} 且不超过 100），返回形状按<b>实际拿到的样子</b>解析
     * （{@code data.messages} / {@code data} 数组 / 单个消息对象都认，见 {@link #history(JsonObject)}）。</p>
     *
     * <p><b>只撤她自己发的</b>：发送者 QQ 必须等于 {@code h.conf().selfId()}；别人的消息一律跳过并写明原因
     * （哪怕她是管理员、哪怕主人点名要撤别人的 —— 这条路不做，遇到就回"只能撤我自己发的"）。
     * 显式 id 也先 {@code get_msg} 核实一次发送者；核实不了（取不回 / 认不出发送者）就带着"未核实"
     * 如实标注后照撤，绝不假装核实过。</p>
     *
     * <p><b>限额与节流</b>：单次最多撤 {@code prompt.md} 的 {@code 撤回最多条数}（默认 20）条，
     * 超出的不报错、只在回执里说"还有 N 条没撤"；条与条之间睡 {@code 撤回间隔毫秒}（默认 300ms）。
     * 判定只在分支开头做一次（{@code send.recall}）—— 逐条结果（成功/失败/跳过）
     * 与平台原始 {@code retcode}/{@code status}/{@code message} 一并回执。</p>
     */
    private static String recall(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— 拉历史、核实发送者、逐条 delete_msg
        // 都是这一个 op 内部的步骤，不再各判一个 op。
        String deny = h.need("send.recall");
        if (deny != null) return deny;
        List<String> ids = recallIds(a);
        boolean byIds = !ids.isEmpty();

        long[] to = new long[] {0L, 0L};
        String scope = Str.trim(a.str("scope"));
        if (Str.has(scope)) {
            if (!parseScope(scope, to)) {
                return msg(c, "撤回scope看不懂", "recall_bad_scope scope={scope}", "{scope}", scope);
            }
        } else {
            to[0] = a.l("group", 0L);           // 兼容写法：本技能别处习惯的 group / qq 也认
            to[1] = a.l("qq", 0L);
        }
        if (to[0] <= 0L && to[1] <= 0L) sessionTarget(h, to);      // 没给 scope → 当前会话
        boolean group = to[0] > 0L;
        long target = group ? to[0] : to[1];

        boolean hasLast = a.has("last");
        boolean hasWindow = a.has("within") || a.has("since") || a.has("until");
        if (!byIds && target <= 0L) return msg(c, "撤回缺会话", "recall_no_session");
        if (!byIds && !hasLast && !hasWindow) return msg(c, "撤回缺范围", "recall_no_range");

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        long self = h.conf() == null ? 0L : h.conf().selfId();
        List<String> candidates = new ArrayList<String>();
        List<String> skippedLines = new ArrayList<String>();
        List<String> notes = new ArrayList<String>();
        int skippedTotal = 0;      // 本次点名的条目里被跳过的（不在自己名下）
        int othersTotal = 0;       // 历史里别人发的条数（压根没进请求集合，一条没碰）
        int scanned = 0;
        String span = "";
        if (byIds) {
            candidates.addAll(ids);
        } else {
            if (self <= 0L) return msg(c, "撤回认不出自己", "recall_no_self");
            String hist = group ? ACT_HIST_GROUP : ACT_HIST_PRIVATE;
            int limit = recallLimit(a);
            int count = recallHistCount(c, limit);
            JsonObject params = group
                    ? J.obj("group_id", target, "count", count)
                    : J.obj("user_id", target, "count", count);
            JsonObject r = h.napcat().call(hist, params);
            if (!Api.ok(r)) {
                return msg(c, "撤回拉不到历史", "recall_history_failed error={error}", "{error}", detail(c, r));
            }
            List<JsonObject> raw = history(r);
            scanned = raw.size();
            if (raw.isEmpty()) {
                notes.add(msg(c, "撤回历史无消息", "recall_history_empty raw={raw}",
                        "{raw}", Str.cut(Str.oneLine(r.toString()), 300)));
            } else if (scanned >= count) {
                notes.add(msg(c, "撤回历史截断", "recall_history_capped count={count}",
                        "{count}", String.valueOf(scanned)));
            }
            List<Hit> own = new ArrayList<Hit>();
            List<Hit> others = new ArrayList<Hit>();
            for (JsonObject m : raw) {
                String id = midOf(m);
                if (Str.blank(id)) continue;
                long from = senderOf(m);
                if (from > 0L && from != self) {
                    others.add(new Hit(id, 0L, from));
                    continue;
                }
                own.add(new Hit(id, msOf(m), from));
            }
            if (!others.isEmpty()) {
                othersTotal = others.size();
                int n = Math.min(RECALL_NOTE_MAX, others.size());
                List<String> otherIds = new ArrayList<String>();
                for (int i = 0; i < n; i++) {
                    Hit x = others.get(i);
                    otherIds.add(x.id);
                    skippedLines.add(skipLine(c, x.id, notMine(c, x.qq, self)));
                }
                notes.add(msg(c, "撤回别人不碰", "recall_others count={count} ids={ids}",
                        "{count}", String.valueOf(others.size()),
                        "{ids}", Str.join(otherIds, "、") + (others.size() > n ? "…" : "")));
            }
            Collections.sort(own, new Comparator<Hit>() {
                @Override
                public int compare(Hit x, Hit y) { return Long.compare(y.ms, x.ms); }
            });
            long[] win = window(a, c);
            span = spanOf(c, win[0], win[1], (int) win[2]);
            int[] outside = new int[] {0};
            for (Hit x : pickRange(own, win[0], win[1], limit, outside)) {
                candidates.add(x.id);
            }
            if (outside[0] > 0) {
                notes.add(msg(c, "撤回窗外", "recall_outside count={count} span={span}",
                        "{count}", String.valueOf(outside[0]), "{span}", span));
            }
        }

        int max = c.recallMax;
        List<String> selected = candidates.size() > max
                ? new ArrayList<String>(candidates.subList(0, max)) : candidates;
        int rest = candidates.size() - selected.size();

        boolean canVerify = byIds && self > 0L;
        if (byIds && self <= 0L) {
            notes.add(msg(c, "撤回无法核实", "recall_unverified_all error={error}",
                    "{error}", msg(c, "撤回读不到自己", "recall_self_unknown")));
        }

        List<String> failed = new ArrayList<String>();
        List<String> unverified = new ArrayList<String>();
        int recalled = 0;
        for (int i = 0; i < selected.size(); i++) {
            String id = selected.get(i);
            if (canVerify) {
                JsonObject g = h.napcat().call(ACT_GET_MSG, J.obj("message_id", numOrStr(id)));
                long from = 0L;
                String why = detail(c, g);
                if (Api.ok(g)) {
                    JsonObject d = J.sub(g, "data");
                    from = d == null ? 0L : senderOf(d);
                    if (from <= 0L) why = msg(c, "撤回认不出发送者", "recall_sender_unknown");
                }
                if (from > 0L && from != self) {
                    skippedLines.add(skipLine(c, id, notMine(c, from, self)));
                    skippedTotal++;
                    continue;
                }
                if (from <= 0L) unverified.add(id + "（" + why + "）");
            }
            JsonObject r = h.napcat().call(ACT_DELETE, J.obj("message_id", numOrStr(id)));
            if (Api.ok(r)) {
                recalled++;
            } else {
                failed.add(failLine(c, id, detail(c, r)));
            }
            if (i + 1 < selected.size()) pause(h, c);
        }

        int requested = selected.size();
        int miss = failed.size() + skippedTotal;
        String where = target > 0L ? targetLabel(c, group, target) : msg(c, "撤回目标未指定", "recall_target_none");
        List<String> out = new ArrayList<String>();
        if (requested <= 0) {
            out.add(msg(c, "撤回范围空", "recall_none target={target} scanned={scanned} span={span}",
                    "{target}", where, "{scanned}", String.valueOf(scanned), "{span}", span));
        } else if (recalled <= 0) {
            out.add(msg(c, "撤回全失败", "recall_all_failed requested={requested} target={target}",
                    "{requested}", String.valueOf(requested), "{target}", where));
        } else if (miss <= 0) {
            out.add(msg(c, "撤回成功", "recalled count={count} target={target}",
                    "{count}", String.valueOf(recalled), "{target}", where));
        } else {
            out.add(msg(c, "撤回部分", "recall_partial count={count} miss={miss} target={target}",
                    "{count}", String.valueOf(recalled), "{miss}", String.valueOf(miss), "{target}", where));
        }
        out.addAll(failed);
        out.addAll(skippedLines);
        if (!unverified.isEmpty()) {
            out.add(msg(c, "撤回未核实", "recall_unverified detail={detail}",
                    "{detail}", Str.join(unverified, "；")));
        }
        if (rest > 0) {
            out.add(msg(c, "撤回超上限", "recall_capped max={max} rest={rest}",
                    "{max}", String.valueOf(max), "{rest}", String.valueOf(rest)));
        }
        for (String n : notes) {
            if (Str.has(n)) out.add(n);
        }
        out.add(msg(c, "撤回汇总",
                "recall_summary requested={requested} recalled={recalled} failed={failed} skipped={skipped} others={others} capped={capped}",
                "{requested}", String.valueOf(requested), "{recalled}", String.valueOf(recalled),
                "{failed}", String.valueOf(failed.size()), "{skipped}", String.valueOf(skippedTotal),
                "{others}", String.valueOf(othersTotal), "{capped}", String.valueOf(rest)));
        return Str.join(out, "\n");
    }

    /** 一个"待撤的消息"（id + 时间毫秒 + 发送者 QQ；拿不到的填 0）。 */
    private static final class Hit {
        final String id;
        final long ms;
        final long qq;
        Hit(String id, long ms, long qq) {
            this.id = id;
            this.ms = ms;
            this.qq = qq;
        }
    }

    /**
     * 撤回的落点：{@code scope}（{@code group:<群号>} / {@code user:<QQ>} / {@code qq:<QQ>}）优先；
     * 没给 scope 时兼容 {@code group} / {@code qq} 两个参数；一个都没有才回落当前会话。
     * 与 {@code record} 同一口径：<b>不拿"控制台不是 QQ 会话"当挡箭牌</b> ——
     * 控制台只是认不出目标，技能会明说"缺会话 + 正确用法"。
     */
    private static boolean parseScope(String scope, long[] to) {
        int i = scope.indexOf(':');
        if (i <= 0) return false;
        String kind = scope.substring(0, i).trim().toLowerCase();
        long v = parseLong(scope.substring(i + 1));
        if (v <= 0L) return false;
        if ("group".equals(kind) || "群".equals(kind)) {
            to[0] = v;
            return true;
        }
        if ("user".equals(kind) || "qq".equals(kind) || "private".equals(kind) || "私聊".equals(kind)) {
            to[1] = v;
            return true;
        }
        return false;
    }

    /** 当前会话键（{@code group:<群号>} / {@code qq:<QQ>} / {@code user:<QQ>}）→ {@code {群号, QQ}}。 */
    private static void sessionTarget(Host h, long[] to) {
        String s = Str.trim(h.session());
        if (s.regionMatches(true, 0, "group:", 0, 6)) {
            to[0] = parseLong(s.substring(6));
        } else if (s.regionMatches(true, 0, "qq:", 0, 3) || s.regionMatches(true, 0, "user:", 0, 5)) {
            to[1] = parseLong(s.substring(s.indexOf(':') + 1));
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(Str.trim(s));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 要撤的显式 id：{@code message_ids}（数组 / 逗号串）+ {@code message_id}（单条或数组）都收。 */
    private static List<String> recallIds(Args a) {
        List<String> out = new ArrayList<String>();
        collectIds(out, J.get(a.raw(), "message_ids"));
        collectIds(out, J.get(a.raw(), "message_id"));
        return out;
    }

    private static void collectIds(List<String> out, JsonElement e) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectIds(out, x);
            return;
        }
        if (!e.isJsonPrimitive()) return;
        for (String p : Str.split(e.getAsString(), ",")) {
            String s = Str.trim(p);
            if (s.isEmpty() || out.contains(s)) continue;
            out.add(s);
        }
    }

    /**
     * 要撤的条数上限（{@code last}）：给了就按它（默认 1、上限 100）；
     * <b>没给就返回 0 = 不按条数限</b> —— 只给时间窗时取窗口内她自己发的<b>全部</b>
     * （"给定一个范围就批量撤"要的就是这个），真正的截断交给单次上限
     * {@code 撤回最多条数}，并由"还有 N 条没撤"那句如实报告。
     */
    private static int recallLimit(Args a) {
        if (a.has("last")) return atLeast(a.l("last", 1L), 1, RECALL_HIST_MAX);
        return 0;
    }

    /** 拉历史的 {@code count}：{@code max(撤回历史条数, 要撤的条数*3)}，且不超过 100。 */
    private static int recallHistCount(Cfg c, int limit) {
        long n = Math.max((long) c.histCount, (long) limit * 3L);
        return atLeast(n, 1, RECALL_HIST_MAX);
    }

    /**
     * 时间窗 → {@code {起点毫秒, 终点毫秒, 秒数}}。
     *
     * <p>{@code since}/{@code until}（毫秒时间戳）优先：给了它们就按区间来，{@code sec = 0}，
     * {@code until} 没给 = {@code Long.MAX_VALUE}（到现在）。否则用 {@code within} 秒（没给就用配置的
     * {@code 撤回默认时间窗秒}，默认 120 秒），起点 = 此刻往前推，终点 = 到现在。</p>
     */
    private static long[] window(Args a, Cfg c) {
        if (a.has("since") || a.has("until")) {
            long from = a.l("since", 0L);
            long until = a.l("until", 0L);
            if (until <= 0L) until = Long.MAX_VALUE;
            if (from < 0L) from = 0L;
            return new long[] {from, until, 0L};
        }
        int sec = atLeast(a.has("within") ? a.l("within", 0L) : (long) c.windowSec, 1, 86400);
        return new long[] {System.currentTimeMillis() - (long) sec * 1000L, Long.MAX_VALUE, sec};
    }

    /**
     * 从"她自己发的消息"里挑出要撤的：<b>窗口内的全部留下</b>（顺序就是传进来的时间顺序，新的在前），
     * 被窗口挡掉的数进 {@code outside[0]}；最后按 {@code limit} 只取前几条。
     *
     * <p>时间拿不到（{@code ms == 0}）的<b>不算窗外</b> —— 判不了就别替调用方判，宁可撤也不假装"不在窗口里"。</p>
     */
    private static List<Hit> pickRange(List<Hit> own, long from, long until, int limit, int[] outside) {
        List<Hit> in = new ArrayList<Hit>();
        for (Hit x : own) {
            if (x.ms > 0L && (x.ms < from || x.ms > until)) {
                if (outside != null && outside.length > 0) outside[0]++;
                continue;
            }
            in.add(x);
        }
        if (limit > 0 && in.size() > limit) return new ArrayList<Hit>(in.subList(0, limit));
        return in;
    }

    /**
     * 历史返回 → 消息对象表（<b>防御式解析</b>：不同实现的形状不一样，按实际拿到的认）。
     *
     * <p>认这些形状：{@code data.messages[]}、{@code data} 本身就是数组、{@code data} 是单个消息对象，
     * 以及顶层 {@code messages[]}；对象里还有 {@code msg_list}/{@code msgList}/{@code list}/{@code message}
     * 一层套的时候继续往下找。认不出来就回空表（调用方会带上原始 JSON 片段说明）。</p>
     */
    private static List<JsonObject> history(JsonObject r) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        collectMsgs(out, J.get(r, "data"));
        if (out.isEmpty()) collectMsgs(out, J.get(r, "messages"));
        return out;
    }

    private static void collectMsgs(List<JsonObject> out, JsonElement e) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectMsgs(out, x);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (Str.has(midOf(o))) {                      // 它自己就是一个消息对象
            out.add(o);
            return;
        }
        String[] keys = {"messages", "msg_list", "msgList", "list", "message"};
        for (String k : keys) {
            JsonElement x = J.get(o, k);
            if (x == null) continue;
            int before = out.size();
            collectMsgs(out, x);
            if (out.size() > before) return;          // 只认第一个真有货的键（messages 优先）
        }
    }

    /** 消息 id（{@code message_id} / {@code msg_id} / {@code id} / {@code real_id} 都认）。 */
    private static String midOf(JsonObject m) {
        String id = J.s(m, "message_id", "");
        if (Str.blank(id)) id = J.s(m, "msg_id", "");
        if (Str.blank(id)) id = J.s(m, "id", "");
        if (Str.blank(id)) id = J.s(m, "real_id", "");
        return Str.trim(id);
    }

    /** 发送者 QQ（{@code sender.user_id} 优先，其次各种扁平写法）；认不出来是 0。 */
    private static long senderOf(JsonObject m) {
        JsonObject s = J.sub(m, "sender");
        long qq = s == null ? 0L : J.l(s, "user_id", 0L);
        if (qq <= 0L && s != null) qq = J.l(s, "uin", 0L);
        if (qq <= 0L) qq = J.l(m, "user_id", 0L);
        if (qq <= 0L) qq = J.l(m, "sender_id", 0L);
        if (qq <= 0L) qq = J.l(m, "sender_uin", 0L);
        if (qq <= 0L) qq = J.l(m, "uin", 0L);
        return qq;
    }

    /** 消息时间 → 毫秒（OneBot 的 {@code time} 是秒；已经是毫秒的就别乘了）。认不出来是 0。 */
    private static long msOf(JsonObject m) {
        long t = J.l(m, "time", 0L);
        if (t <= 0L) t = J.l(m, "timestamp", 0L);
        if (t <= 0L) return 0L;
        return t < 100000000000L ? t * 1000L : t;
    }

    /** 时间窗的人话（{@code sec > 0} = "最近 N 秒内"，否则按 since/until 区间说）。 */
    private static String spanOf(Cfg c, long from, long until, int sec) {
        if (sec > 0) {
            return msg(c, "撤回窗最近", "recall_window_recent sec={sec}", "{sec}", String.valueOf(sec));
        }
        String a = from > 0L ? when(c, from) : msg(c, "撤回窗起点不限", "recall_window_start_any");
        String b = until == Long.MAX_VALUE ? msg(c, "撤回窗终点现在", "recall_window_end_now") : when(c, until);
        return msg(c, "撤回窗区间", "recall_window from={from} to={to}", "{from}", a, "{to}", b);
    }

    /** "只能撤我自己发的"那句（把对方的 QQ 与我自己的 QQ 都点明，别让调用方猜）。 */
    private static String notMine(Cfg c, long qq, long self) {
        String who = qq > 0L ? String.valueOf(qq) : msg(c, "撤回发送者未知", "recall_sender_unknown_who");
        return msg(c, "只能撤自己", "recall_not_mine qq={qq} self={self}",
                "{qq}", who, "{self}", String.valueOf(self));
    }

    private static String failLine(Cfg c, String id, String error) {
        return msg(c, "撤回失败行", "recall_bad id={id} error={error}", "{id}", id, "{error}", error);
    }

    private static String skipLine(Cfg c, String id, String why) {
        return msg(c, "撤回跳过行", "recall_skip id={id} why={why}", "{id}", id, "{why}", why);
    }

    /** 条间隔（默认 300ms，配置可调）：避免连着撤触发平台风控。 */
    private static void pause(Host h, Cfg c) {
        if (c.gapMs <= 0) return;
        try {
            Thread.sleep(c.gapMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(h, "撤回间隔被中断，后面的条目不睡间隔了");
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 消息内容归一

    /** 消息体（CQ 字符串或消息段数组）→ 纯文本（图片一类只留占位符）。 */
    private static String messageText(Cfg c, JsonElement body) {
        if (body == null || body.isJsonNull()) return "";
        if (body.isJsonPrimitive()) return cq(c, body.getAsString());
        if (body.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : body.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject seg = e.getAsJsonObject();
                sb.append(segText(c, J.s(seg, "type", ""), J.sub(seg, "data")));
            }
            return sb.toString().trim();
        }
        return "";
    }

    /** CQ 码字符串 → 纯文本。 */
    private static String cq(Cfg c, String raw) {
        String s = Str.nz(raw);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int p = s.indexOf("[CQ:", i);
            if (p < 0) {
                sb.append(s.substring(i));
                break;
            }
            sb.append(s.substring(i, p));
            int q = s.indexOf(']', p);
            if (q < 0) {
                sb.append(s.substring(p));
                break;
            }
            String[] parts = s.substring(p + 4, q).split(",");
            String type = parts.length > 0 ? parts[0].trim() : "";
            JsonObject data = new JsonObject();
            for (int k = 1; k < parts.length; k++) {
                int eq = parts[k].indexOf('=');
                if (eq <= 0) continue;
                data.addProperty(parts[k].substring(0, eq).trim(), parts[k].substring(eq + 1).trim());
            }
            sb.append(segText(c, type, data));
            i = q + 1;
        }
        return sb.toString().trim();
    }

    /** 一个消息段 → 占位符（非文本一律不下载、不转存）。 */
    private static String segText(Cfg c, String type, JsonObject data) {
        if ("text".equals(type)) return Str.nz(J.s(data, "text", ""));
        if ("at".equals(type)) return fill(c.phAt, "{qq}", J.s(data, "qq", ""));
        if ("image".equals(type)) return c.phImage;
        if ("record".equals(type)) return c.phRecord;
        if ("face".equals(type)) return c.phFace;
        if ("file".equals(type)) return c.phFile;
        if ("video".equals(type)) return c.phVideo;
        if ("forward".equals(type)) return c.phForward;
        if ("reply".equals(type)) return "";
        return c.phCard;
    }

    /** 从消息体里挖出合并转发的 id（消息段数组与 CQ 字符串两种形状）。 */
    private static List<String> forwardIds(JsonElement body) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.isJsonNull()) return out;
        if (body.isJsonArray()) {
            for (JsonElement e : body.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject seg = e.getAsJsonObject();
                if (!"forward".equals(J.s(seg, "type", ""))) continue;
                String id = J.s(J.sub(seg, "data"), "id", "");
                if (Str.has(id)) out.add(id.trim());
            }
            return out;
        }
        if (!body.isJsonPrimitive()) return out;
        String s = body.getAsString();
        int i = 0;
        while (i < s.length()) {
            int p = s.indexOf("[CQ:forward", i);
            if (p < 0) break;
            int q = s.indexOf(']', p);
            if (q < 0) break;
            for (String part : s.substring(p + 4, q).split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                if (!"id".equals(part.substring(0, eq).trim())) continue;
                String v = part.substring(eq + 1).trim();
                if (Str.has(v)) out.add(v);
            }
            i = q + 1;
        }
        return out;
    }

    // ================================================================ 小工具

    /** 要转述的消息 id：数组、逗号分隔字符串、单个 id 三种都给面子。 */
    private static List<String> ids(Args a) {
        List<String> out = new ArrayList<String>();
        List<String> arr = a.strs("ids");
        if (arr != null) for (String s : arr) if (Str.has(s)) out.add(s.trim());
        if (out.isEmpty()) out.addAll(Str.split(a.str("ids"), ","));
        if (out.isEmpty()) {
            long id = a.l("id", 0L);
            if (id > 0L) out.add(String.valueOf(id));
        }
        return out;
    }

    /** {@code at} 参数 → QQ 号列表（"all" 原样保留表示 @全体）。 */
    private static List<String> atList(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            while (t.startsWith("@")) t = t.substring(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 文本 + @ 段（走消息段数组，避免把正文当 CQ 码解析）。 */
    private static JsonArray segs(String text, List<String> ats) {
        JsonArray arr = new JsonArray();
        for (String at : ats) {
            String q = AT_ALL.equalsIgnoreCase(at) ? AT_ALL : at;
            arr.add(J.obj("type", "at", "data", J.obj("qq", q)));
        }
        arr.add(J.obj("type", "text", "data", J.obj("text", " " + text)));
        return arr;
    }

    /** 纯数字的消息 id 发数字，其余原样发字符串（不同实现取值习惯不一样）。 */
    private static Object numOrStr(String id) {
        String s = Str.trim(id);
        if (s.matches("^\\d{1,18}$")) {
            try {
                return Long.valueOf(Long.parseLong(s));
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    private static String targetLabel(Cfg c, boolean group, long id) {
        return group ? msg(c, "目标群", "group={group}", "{group}", String.valueOf(id))
                : msg(c, "目标人", "qq={qq}", "{qq}", String.valueOf(id));
    }

    private static String badLine(Cfg c, String id, String error) {
        return msg(c, "转述失败行", "forward_bad id={id} error={error}", "{id}", id, "{error}", error);
    }

    /**
     * 失败的<b>原始证据</b>：把平台回执里的 {@code retcode} / {@code status} / {@code message}
     * 一并带上（别让调用方只知道"失败了"）。NapCat 拒绝一个动作时说的原话就在这里，
     * 例如猜错动作名会得到 {@code retcode=1404 status=failed message=不支持的Api send_record}。
     */
    private static String detail(Cfg c, JsonObject r) {
        if (r == null) return msg(c, "未知结果", "unknown_result");
        List<String> parts = new ArrayList<String>();
        if (J.get(r, "retcode") != null) parts.add("retcode=" + J.s(r, "retcode", ""));
        String status = Str.trim(J.s(r, "status", ""));
        if (Str.has(status)) parts.add("status=" + status);
        String m = Str.trim(J.s(r, "message", ""));
        if (m.isEmpty()) m = Str.trim(J.s(r, "wording", ""));
        if (m.isEmpty()) m = Str.trim(Api.error(r));
        if (Str.has(m)) parts.add("message=" + Str.cut(Str.oneLine(m), 200));
        if (parts.isEmpty()) return msg(c, "未知结果", "unknown_result");
        return Str.join(parts, " ");
    }

    private static String when(Cfg c, long ms) {
        if (ms <= 0L) return "";
        try {
            return new SimpleDateFormat(c.timeFmt).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String pick(String... cands) {
        if (cands == null) return "";
        for (String s : cands) if (Str.has(s)) return s.trim();
        return "";
    }

    // ================================================================ 配置 / 文案

    /** {@code prompt.md} 是唯一配置源：读不到 / 缺项 = 那一项不生效（Java 里只有结构性兜底）。 */
    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = "";
        try {
            File dir = h == null ? null : h.skillDir();
            if (dir == null || !dir.isDirectory()) {
                if (h != null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
            }
            text = Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable ignored) {
        }
        if (Str.blank(text)) return c;
        Map<String, String> kv = pairs(section(text, "配置"));
        c.texts = pairs(section(text, "文案"));
        c.max = atLeast(num(kv.get("最多转述条数"), 10L), 1, FORWARD_HARD_MAX);
        c.recallMax = atLeast(num(kv.get("撤回最多条数"), 20L), 1, RECALL_HARD_MAX);
        c.gapMs = atLeast(num(kv.get("撤回间隔毫秒"), 300L), 0, 10000);
        c.windowSec = atLeast(num(kv.get("撤回默认时间窗秒"), 120L), 1, 86400);
        c.histCount = atLeast(num(kv.get("撤回历史条数"), 50L), 1, RECALL_HIST_MAX);
        c.timeFmt = def(kv.get("时间格式"), c.timeFmt);
        c.head = def(kv.get("转述头"), c.head);
        c.line = def(kv.get("转述行"), c.line);
        c.nestLine = def(kv.get("转述嵌套行"), c.nestLine);
        c.fromDef = Str.nz(kv.get("出处缺省"));
        c.sep = spaced(kv.get("转述分隔")) ? "\n\n" : "\n";
        c.phImage = def(kv.get("占位图片"), c.phImage);
        c.phRecord = def(kv.get("占位语音"), c.phRecord);
        c.phFace = def(kv.get("占位表情"), c.phFace);
        c.phFile = def(kv.get("占位文件"), c.phFile);
        c.phVideo = def(kv.get("占位视频"), c.phVideo);
        c.phCard = def(kv.get("占位卡片"), c.phCard);
        c.phForward = def(kv.get("占位合并转发"), c.phForward);
        c.phAt = def(kv.get("占位@"), c.phAt);
        return c;
    }

    private static boolean spaced(String v) {
        String s = Str.trim(v);
        return "空行".equals(s) || "blank".equalsIgnoreCase(s) || "2".equals(s);
    }

    private static final class Cfg {
        int max = 10;
        int recallMax = 20;
        int gapMs = 300;
        int windowSec = 120;
        int histCount = 50;
        String timeFmt = "MM-dd HH:mm";
        String head = "forward_head from={from} count={count}";
        String line = "{time} {name}({qq})：{text}";
        String nestLine = "{time} {name}({qq})：{text}";
        String fromDef = "";
        String sep = "\n";
        String phImage = "[image]";
        String phRecord = "[record]";
        String phFace = "[face]";
        String phFile = "[file]";
        String phVideo = "[video]";
        String phCard = "[card]";
        String phForward = "[forward]";
        String phAt = "@{qq}";
        Map<String, String> texts = new LinkedHashMap<String, String>();
    }

    /** 取 {@code ## 文案} 的模板渲染；模板缺失时用调用方给的结构性兜底。 */
    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c.texts == null ? null : c.texts.get(key);
        return fill(Str.has(tpl) ? tpl : fallback, kv);
    }

    /** {@code {k}} → 值。 */
    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

    /** 取 md 里 {@code ## 名} 到下一个 {@code ## } 之间的正文。 */
    private static String section(String text, String name) {
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

    /** {@code 键: 值} 一行一条 → map（跳过注释与没有分隔符的行）。 */
    private static Map<String, String> pairs(String body) {
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

    private static String def(String v, String fallback) {
        return Str.has(v) ? v.trim() : fallback;
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int atLeast(long v, int min, int max) {
        long x = v < min ? min : v;
        return (int) (x > max ? max : x);
    }

    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }
}
