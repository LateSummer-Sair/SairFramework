import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;
import sair.v4.store.Store;

/**
 * 视觉识图（工具 {@code vision}，op {@code see}）：URL / 本地路径 / NapCat fileid → 基板的多模态调用。
 *
 * <p>图源：{@code http(s)} 链接<b>先过基板 SSRF 闸门</b>（{@code kit/NetGuard}：环回/私网/链路本地/云元数据
 * 一律拒绝）再交给模型；本地路径按数据目录解析并做 <b>canonical 前缀校验</b>
 * （{@code ..}、绝对越界、符号链接逃逸一律拒绝），读成 {@code data:image/…;base64,…}；
 * 没有路径分隔符的裸串按 NapCat 的 fileid 处理：<b>优先用事件里的图片 URL</b>（跨机可取），
 * 其次 {@code get_image} 的 http 链接 / base64，最后才是本地缓存路径<b>直读（只当同机快路径）</b>。</p>
 *
 * <p>缓存与永久失败黑名单都存 {@code store.kv}（前缀 {@code vision:}）：
 * 缓存键是"图 + 问题"的 md5，黑名单键是"图"的 md5。
 * <b>永久失败</b>（模型/用户给的路径不存在、读不了、不是图片、太大）才拉黑；
 * <b>NapCat 侧与传输侧的一切失败</b>（fileid 取不回、get_image 给的是另一台机器的路径、base64 解不出、
 * 内网拒绝）都是<b>临时失败</b>：只回说明 + 记 {@code vision:last}，<b>绝不进黑名单</b> ——
 * 跨机部署、缓存没落盘、稍后重试都可能救回来；模型侧与环境侧失败（没 key、模型报错、空结果）同样不拉黑。</p>
 */
public class VisionSkill implements Skill {

    /** 技能名（= 文件夹名 = md 文件名）。 */
    private static final String NAME = "视觉识图";
    /** kv 前缀（结构性）。 */
    private static final String KV_CACHE = "vision:cache:";
    private static final String KV_BAD = "vision:bad:";
    private static final String KV_LAST = "vision:last";
    /** 取图片文件的 NapCat 动作。 */
    private static final String ACT_GET_IMAGE = "get_image";
    /** 默认提示词缺失时的结构性兜底。 */
    private static final String ASK_FALLBACK = "describe this image";

    // ================================================================ 入口

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        if (h == null) return "[" + NAME + "] host=null";
        try {
            String op = Str.trim(a.str("op")).toLowerCase();
            if (op.isEmpty()) op = "see";
            if ("see".equals(op)) return see(a, h, c);
            return msg(c, "不支持op", "unsupported_op op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "工具调用异常：" + t);
            return msg(c, "异常", "error={error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ op：see

    private static String see(Args a, Host h, Cfg c) {
        String image = Str.trim(a.str("image"));
        if (image.isEmpty()) return msg(c, "缺图片", "missing_image");
        String ask = Str.trim(a.str("prompt"));
        if (ask.isEmpty()) ask = c.ask;
        if (ask.isEmpty()) ask = ASK_FALLBACK;
        boolean refresh = a.b("refresh", false);

        // 出网调模型 = 执行一个工具 → 判这个工具的 X 位（T 类工具的运行时形态）。
        // T 类默认分配是 ALLUSER 无 / SYSTEM RWX / MASTER RWX ⇒ 非主人用不了这把工具，要特例放行
        // （可被账本例外放开的位判定）。
        String deny = h.need(sair.v4.auth.Res.tool("vision"), 'X');
        if (deny != null) return deny;

        Store store = h.store();
        long now = System.currentTimeMillis();
        String badKey = KV_BAD + md5(image);
        String cacheKey = KV_CACHE + md5(image + "\n" + ask);

        // ① 永久失败黑名单（refresh 时放行重试一次）
        JsonObject bad = store == null ? null : J.obj(store.kv(badKey, ""));
        if (bad != null && !refresh) {
            return msg(c, "黑名单", "vision_blacklisted reason={reason}",
                    "{reason}", Str.cut(J.s(bad, "reason", ""), 60));
        }

        // ② 结果缓存
        JsonObject hit = store == null ? null : J.obj(store.kv(cacheKey, ""));
        if (hit != null && c.cacheEnabled && !refresh && fresh(hit, c, now)) {
            long age = Math.max(0L, (now - J.l(hit, "ts", now)) / 60000L);
            return render(c, J.s(hit, "text", ""), J.s(hit, "image", image), J.s(hit, "model", ""), 0L, true, age);
        }

        // ③ 图源 → 交给模型的东西
        Pic pic = resolve(h, c, image);
        if (Str.has(pic.error)) {
            if (pic.permanent && store != null) {
                // reason 存人话（给下一次的说明用），code 存结构性标记（给机器看）
                store.kvSet(badKey, J.json(J.obj("ts", now, "code", pic.reason,
                        "reason", Str.cut(pic.error, 120), "image", image)));
            } else if (store != null) {
                // 临时失败：只记状态，绝不拉黑（跨机取图失败、NapCat 缓存没落盘都属于这类）
                store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", false, "image", image,
                        "temp", true, "code", pic.reason, "error", Str.cut(pic.error, 200))));
            }
            return pic.error;
        }

        // ④ 没 key 就不打网络（明确说明，不崩、不拉黑）
        String key = h.ai() == null ? "" : h.ai().apiKey();
        if (Str.blank(key)) {
            String url = h.conf() == null ? "" : Str.nz(h.conf().apiUrl());
            if (store != null) store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", false, "image", image, "error", "no_api_key")));
            return msg(c, "无key", "vision_no_key api_url={apiUrl}", "{apiUrl}", url);
        }

        // ⑤ 视觉兜底：主模型不会看图时，派一个用"会看图的模型"的子 Agent 去看，
        //    让它把看到的内容用文字回传（配置 visionModel 缺省即基板的视觉模型）
        String mainModel = h.conf() == null ? "" : Str.nz(h.conf().resolveModel());
        String visionModel = h.conf() == null ? "" : Str.nz(h.conf().visionModel());
        // R3 / 3b：改走 ConfView.visionModels()（新 jar 起可用），与基板 CtxBuild.selfHasVision（:250-252）
        // **同一个默认值来源**（缺键 = "deepseek-flash"）⇒ 不再有"缺键传空串"的形式差异，两处同源。
        // 判据本身仍是 CtxBuild.looksVisionCapable（硬白名单 + 配置追加项），**不新造第二套判定**。
        if (!sair.v4.ctx.CtxBuild.looksVisionCapable(mainModel, h.conf() == null ? "" : h.conf().visionModels(), visionModel) && Str.has(visionModel)) {
            String brief = msg(c, "兜底说明", "vision_fallback_brief {ask}", "{ask}", ask);
            String task = msg(c, "兜底任务", "vision_fallback_task {ask}\n{url}", "{ask}", ask, "{url}", pic.url);
            JsonObject ticket = h.spawn(task, null, false, brief, visionModel);
            String result = ticket == null ? "" : Str.trim(sair.v4.kit.J.s(ticket, "result", ""));
            if (Str.has(result)) {
                if (store != null) {
                    store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", true, "image", image,
                            "model", visionModel, "fallback", true)));
                }
                return result;
            }
            String error = ticket == null ? "no_ticket" : Str.cut(Str.oneLine(sair.v4.kit.J.s(ticket, "error", "")), 300);
            if (store != null) {
                store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", false, "image", image,
                        "model", visionModel, "fallback", true, "error", error)));
            }
            return msg(c, "兜底失败", "vision_fallback_failed model={model} error={error}",
                    "{model}", visionModel, "{error}", error);
        }

        // ⑥ 多模态调用
        List<String> urls = new ArrayList<String>();
        urls.add(pic.url);
        JsonArray messages = new JsonArray();
        messages.add(Msg.userWithImages(ask, urls));
        JsonObject opts = new JsonObject();
        if (Str.has(c.model)) opts.addProperty("model", c.model);
        Res r = h.ai().vision(messages, opts);
        if (r == null || !r.ok()) {
            String error = r == null ? "null" : Str.cut(Str.oneLine(r.error), 300);
            if (store != null) store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", false, "image", image, "error", error)));
            return msg(c, "模型失败", "vision_model_failed error={error}", "{error}", error);
        }

        String text = Str.trim(r.text());
        if (text.isEmpty()) {
            if (store != null) store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", false, "image", image, "error", "empty")));
            return msg(c, "空结果", "vision_empty");
        }

        String model = Str.has(r.modelUsed) ? r.modelUsed
                : (h.conf() == null ? "" : Str.nz(h.conf().resolveModel()));

        // ⑥ 落缓存 / 摘黑名单 / 记状态
        if (store != null) {
            if (c.cacheEnabled) {
                store.kvSet(cacheKey, J.json(J.obj("ts", now, "text", text, "image", image, "model", model, "ms", r.ms)));
            }
            if (bad != null) store.kvDelete(badKey, false);
            store.kvSet(KV_LAST, J.json(J.obj("ts", now, "ok", true, "image", image,
                    "cached", false, "ms", r.ms, "model", model)));
        }
        return render(c, text, image, model, r.ms, false, 0L);
    }

    // ================================================================ 图源解析

    /** 交给模型的东西（或一段说明）。 */
    private static final class Pic {
        String url = "";
        String error = "";
        String reason = "";
        boolean permanent = false;
    }

    private static Pic resolve(Host h, Cfg c, String image) {
        String low = Str.lower(image);
        if (low.startsWith("http://") || low.startsWith("https://")) return byUrl(c, image);
        if (low.startsWith("data:image/")) {
            Pic p = new Pic();
            p.url = image;
            return p;
        }
        if (isFileId(image)) return byFileId(h, c, image);
        return byPath(h, c, image);
    }

    /**
     * 直连 URL：<b>先过基板 SSRF 闸门</b>（环回/私网/链路本地/云元数据一律拒绝），
     * 过了才把 URL 交给模型。被拒不是"图的错"，不拉黑。
     */
    private static Pic byUrl(Cfg c, String url) {
        Pic p = new Pic();
        sair.v4.kit.NetGuard.Verdict v = sair.v4.kit.NetGuard.check(url);
        if (v.blocked) {
            p.error = msg(c, "内网拒绝", "vision_denied_internal code={code} host={host}",
                    "{code}", v.code, "{host}", v.host, "{reason}", v.reason, "{image}", Str.cut(url, 200));
            p.reason = v.code;
            return p;
        }
        p.url = url;
        return p;
    }

    /** 裸串（没有分隔符、没有盘符、没有协议）才当 NapCat 的 fileid；数据目录里有同名文件时优先当路径。 */
    private static boolean isFileId(String image) {
        String s = Str.trim(image);
        if (s.isEmpty()) return false;
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) return false;
        if (s.indexOf(':') >= 0) return false;
        return true;
    }

    /** 本地路径：canonical 前缀校验（必须落在数据目录内）→ 读字节 → data URL。 */
    private static Pic byPath(Host h, Cfg c, String raw) {
        Pic p = new Pic();
        File root = h.conf() == null ? null : h.conf().root();
        if (root == null) {
            p.error = msg(c, "异常", "error={error}", "{error}", "no_data_root");
            return p;
        }
        try {
            String t = raw;
            String low = Str.lower(t);
            if (low.startsWith("file://")) t = t.substring(7);
            else if (low.startsWith("file:")) t = t.substring(5);
            if (t.length() >= 3 && t.charAt(0) == '/' && Character.isLetter(t.charAt(1)) && t.charAt(2) == ':') {
                t = t.substring(1);                     // file:///C:/x → C:/x
            }
            File f = new File(t);
            if (!f.isAbsolute()) f = new File(root, t);
            File canon = f.getCanonicalFile();
            if (!inside(root.getCanonicalPath(), canon.getPath())) {
                p.error = msg(c, "越界", "vision_denied path={path}", "{path}", canon.getPath());
                return p;                               // 越界是策略拒绝，不拉黑（改对路径就能用）
            }
            return read(h, c, canon, true);
        } catch (Throwable t) {
            p.error = msg(c, "读不了", "vision_unreadable error={error}", "{error}", String.valueOf(t));
            p.reason = String.valueOf(t);
            p.permanent = true;
            return p;
        }
    }

    /**
     * NapCat fileid → 可交给模型的东西。顺序刻意这样排：
     * <ol>
     *   <li><b>事件里的图片 URL</b>（基板 {@code Inbound} 登记簿）：NapCat 与插件不同机时，这是唯一
     *       一定能取到图的线索 —— 事件本身就带一条可直连的 URL；</li>
     *   <li>{@code get_image} 的返回：先 {@code data.url}（http 链接），再 <b>base64</b>
     *       （{@code data.data} / {@code data.base64}），最后才看 {@code data.file} 本地路径；</li>
     *   <li>本地路径直读只当<b>同机快路径</b>：读不到就是<b>临时失败</b>（跨机、缓存没落盘都算），
     *       <b>绝不写永久黑名单</b> —— 换台机器或稍后重试就能好。</li>
     * </ol>
     */
    private static Pic byFileId(Host h, Cfg c, String fileid) {
        Pic p = new Pic();
        // ① 事件里的图片 URL（登记簿按 file 值索引，命中就是同一张图）
        String ev = h.inboundImageUrl(fileid);
        if (Str.has(ev)) {
            Pic q = byUrl(c, ev);
            if (!Str.has(q.error)) {
                log(h, "fileid " + fileid + " → 用事件里的图片 URL（跨机可取）");
                return q;
            }
            log(h, "fileid " + fileid + " 的事件 URL 也被闸门拒了：" + q.reason);
        }
        // ② get_image
        String deny = h.needPlatform(ACT_GET_IMAGE);
        if (deny != null) {
            p.error = deny;
            return p;
        }
        if (!h.napcatConnected()) {
            p.error = Api.NOT_CONNECTED;
            return p;
        }
        JsonObject r = h.napcat().call(ACT_GET_IMAGE, J.obj("file", fileid));
        if (!Api.ok(r)) {
            String e = Str.has(Api.error(r)) ? Api.error(r) : "get_image";
            p.error = msg(c, "fileid取不回", "vision_fileid_failed error={error}", "{error}", e);
            p.reason = e;
            return p;                                   // 临时失败：NapCat 侧的问题，不拉黑
        }
        JsonObject d = J.sub(r, "data");
        String url = d == null ? "" : J.s(d, "url", "");
        String path = d == null ? "" : J.s(d, "file", "");
        if (Str.has(url) && Str.lower(url).startsWith("http")) {
            Pic q = byUrl(c, Str.trim(url));
            if (!Str.has(q.error)) return q;
            p.error = q.error;
            p.reason = q.reason;
            return p;
        }
        if (Str.has(path) && Str.lower(Str.trim(path)).startsWith("http")) {
            Pic q = byUrl(c, Str.trim(path));
            if (!Str.has(q.error)) return q;
            p.error = q.error;
            p.reason = q.reason;
            return p;
        }
        // ③ base64（NapCat 某些版本把图直接塞在 data.data / data.base64 里）
        byte[] inline = base64Of(d);
        if (inline != null && inline.length > 0) {
            if (c.maxBytes > 0 && inline.length > c.maxBytes) {
                p.error = msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "too_large_base64");
                p.reason = "too_large";
                return p;
            }
            String mime = mimeOf(inline);
            if (mime == null) {
                p.error = msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "base64_not_image");
                p.reason = "bad_format";
                return p;
            }
            p.url = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(inline);
            log(h, "fileid " + fileid + " → 用 get_image 的 base64（" + inline.length + " 字节，" + mime + "）");
            return p;
        }
        if (Str.blank(path)) {
            p.error = msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "empty");
            p.reason = "empty";
            return p;
        }
        // ④ 同机快路径：NapCat 自己给的本地路径（跨机时这里读不到 → 临时失败）
        try {
            File f = new File(Str.trim(path));
            log(h, "fileid " + fileid + " → NapCat 缓存路径 " + f.getAbsolutePath());
            return read(h, c, f, false);                // NapCat 自己给的路径：不做数据目录前缀校验
        } catch (Throwable t) {
            p.error = msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", String.valueOf(t));
            p.reason = String.valueOf(t);
            return p;
        }
    }

    /** 从 get_image 的返回里取内联 base64（{@code data.data} / {@code data.base64}，容忍 {@code data:} 前缀）。 */
    private static byte[] base64Of(JsonObject d) {
        if (d == null) return null;
        String[] keys = {"data", "base64", "image", "file_data"};
        for (String k : keys) {
            String v = Str.trim(J.s(d, k, ""));
            if (v.length() < 64) continue;               // 太短的不可能是图片 base64（也不可能是 file 值）
            int comma = v.indexOf(',');
            if (v.regionMatches(true, 0, "data:", 0, 5) && comma > 0) v = v.substring(comma + 1);
            if (v.length() < 64) continue;
            try {
                byte[] raw = Base64.getMimeDecoder().decode(v);
                if (raw != null && raw.length > 0) return raw;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 读文件 → 校验大小 / 格式 → data URL。
     *
     * @param fromUser true = 模型/用户给的路径（读不到算永久失败）；
     *                 false = NapCat 侧给的路径（读不到只算<b>临时失败</b>，不进黑名单）
     */
    private static Pic read(Host h, Cfg c, File f, boolean fromUser) {
        Pic p = new Pic();
        boolean temp = !fromUser;                        // NapCat 侧的一切失败都是临时的（跨机/缓存过期）
        String path = f == null ? "" : f.getPath();
        if (f == null || !f.isFile()) {
            p.error = temp
                    ? msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "not_found " + path)
                    : msg(c, "图片不存在", "vision_not_found path={path}", "{path}", path);
            p.reason = "not_found";
            p.permanent = !temp;
            return p;
        }
        long size = f.length();
        if (size <= 0L) {
            p.error = temp
                    ? msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "empty_file " + path)
                    : msg(c, "读不了", "vision_unreadable error={error}", "{error}", "empty_file");
            p.reason = "empty_file";
            p.permanent = !temp;
            return p;
        }
        if (c.maxBytes > 0 && size > c.maxBytes) {
            p.error = temp
                    ? msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "too_large " + size)
                    : msg(c, "太大", "vision_too_large size={size} max={max}",
                            "{size}", String.valueOf(size), "{max}", String.valueOf(c.maxBytes));
            p.reason = "too_large";
            p.permanent = !temp;
            return p;
        }
        // 读本地图片前判"当前主体能不能读这个文件"（ACL A/B 类 R 位；被拦不算图片的问题，绝不拉黑）
        String deny = h.needPath(f, 'R');
        if (deny != null) {
            p.error = deny;
            p.reason = "denied";
            p.permanent = false;
            return p;
        }
        byte[] data;
        try {
            data = Fs.readBytes(f);
        } catch (Throwable t) {
            p.error = temp
                    ? msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", String.valueOf(t))
                    : msg(c, "读不了", "vision_unreadable error={error}", "{error}", String.valueOf(t));
            p.reason = String.valueOf(t);
            p.permanent = !temp;
            return p;
        }
        String mime = mimeOf(data);
        if (mime == null) {
            p.error = temp
                    ? msg(c, "读不到临时", "vision_temp_failed error={error}", "{error}", "bad_format " + path)
                    : msg(c, "格式不支持", "vision_bad_format path={path}", "{path}", path);
            p.reason = "bad_format";
            p.permanent = !temp;
            return p;
        }
        p.url = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
        log(h, (fromUser ? "读图 " : "读 NapCat 缓存 ") + path + "（" + size + " 字节，" + mime + "）");
        return p;
    }

    /** 魔数判格式（不认扩展名，扩展名会骗人）。 */
    private static String mimeOf(byte[] b) {
        if (b == null) return null;
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        return null;
    }

    /** canonical 前缀校验（都换成 / 之后比较，Windows 上忽略大小写）。 */
    private static boolean inside(String base, String path) {
        String b = norm(base);
        String p = norm(path);
        if (p.equals(b)) return true;
        String prefix = b.endsWith("/") ? b : b + "/";
        if (p.startsWith(prefix)) return true;
        if (isWindows()) {
            String bl = b.toLowerCase();
            String pl = p.toLowerCase();
            return pl.equals(bl) || pl.startsWith(bl.endsWith("/") ? bl : bl + "/");
        }
        return false;
    }

    private static String norm(String p) {
        String s = Str.nz(p).replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean isWindows() {
        try {
            return File.separatorChar == '\\';
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ================================================================ 小工具

    private static boolean fresh(JsonObject hit, Cfg c, long now) {
        if (c.cacheMs <= 0L) return true;
        long ts = J.l(hit, "ts", 0L);
        return ts > 0L && now - ts < c.cacheMs;
    }

    private static String md5(String s) {
        return Fs.md5(Str.nz(s).getBytes(Fs.UTF8));
    }

    private static String render(Cfg c, String text, String image, String model, long ms, boolean cached, long ageMin) {
        String extra = cached ? msg(c, "缓存标记", " cached age={age}min", "{age}", String.valueOf(ageMin)) : "";
        return msg(c, "结果", "{text}", "{text}", text) + "\n"
                + msg(c, "结果元信息", "image={image} model={model} ms={ms}{extra}",
                "{image}", image, "{model}", model, "{ms}", String.valueOf(ms), "{extra}", extra);
    }

    // ================================================================ 配置 / 文案

    /** {@code prompt.md} 是唯一配置源：读不到 / 缺项 = 那一项不生效。 */
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
        c.ask = section(text, "提示词");
        Map<String, String> askKv = pairs(c.ask);       // ## 提示词 一节写成 "默认提示词: …" 也认
        if (Str.has(askKv.get("默认提示词"))) c.ask = Str.trim(askKv.get("默认提示词"));
        c.cacheEnabled = truthy(kv.get("启用缓存"));
        c.cacheMs = (long) (dec(kv.get("缓存小时"), 24.0D) * 3600_000.0D);
        c.maxBytes = atLeast(num(kv.get("最大图片字节"), 4194304L), 1024, 64 * 1024 * 1024);
        c.model = Str.trim(kv.get("视觉模型"));
        return c;
    }

    private static final class Cfg {
        boolean cacheEnabled = true;
        long cacheMs = 24L * 3600_000L;
        int maxBytes = 4 * 1024 * 1024;
        String model = "";
        String ask = "";
        Map<String, String> texts = new LinkedHashMap<String, String>();
    }

    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c.texts == null ? null : c.texts.get(key);
        return fill(Str.has(tpl) ? tpl : fallback, kv);
    }

    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

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

    private static int atLeast(long v, int min, int max) {
        long x = v < min ? min : v;
        return (int) (x > max ? max : x);
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
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
