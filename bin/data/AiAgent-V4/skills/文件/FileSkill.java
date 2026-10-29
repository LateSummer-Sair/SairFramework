import java.io.ByteArrayOutputStream;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sair.v4.kit.Fs;
import sair.v4.kit.Http;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 文件（工具 {@code file}）：read / list / find / download / write。
 *
 * <p><b>能碰什么：唯一权威是技能管控表（op），技能不给自己设一道更低的上限</b>。相对路径按 {@code h.conf().root()}
 * 解析（那是"相对于谁"的语义，保留），绝对路径原样采用 —— 主人要读数据根以外的文件
 * （例如 {@code D:\Share\**\x.mp3}）时，挡他的只有授权，不是技能。
 * 每个动作分支开头判自己那个 op（{@code h.need("file.read")} 等）：<b>被拒时把拒绝原文原样返回给调用方</b>
 * （形如 {@code [权限阻断] …}）—— 调用方要靠这句话决定要不要换别的工具，技能另造一句拒绝就会把它带偏。
 * 授权看技能管控表 {@code data/skillctl.json}：{@code Run} 放行才动手、{@code Ban}/空 = 不可用；
 * 主人（{@code MASTER}）与她本人（{@code SYSTEM}）恒全权，不受表影响。</p>
 *
 * <p><b>结构性防护仍在（这些不是权限，别删）</b>：① {@code getCanonicalFile()} 抵掉 {@code ..} 与符号链接；
 * ② 再用 {@code toRealPath()} 把<b>最深一层已存在的祖先</b>解成真实位置 —— Windows 上
 * {@code getCanonicalPath()} 不解析目录联接（junction），只做 ① 会让 {@code files/联接/secret.txt} 静默指到
 * 数据根外面；② 让 junction 与符号链接一起露出来，而且对"还不存在的新文件"（write / download 的落点）同样成立。
 * {@code download} 的落点固定在数据根的 {@code files/}（{@code h.conf().filesDir()}）—— 那是这个 op 的语义，
 * 见 {@link #resolveIn}，两道前缀校验在那儿照旧生效；{@code find} / {@code list} 的扫描条数与深度上限也照旧。</p>
 *
 * <p><b>零硬编码文案</b>：阈值与全部面向模型/用户的文本都读自同目录 {@code prompt.md}（每次调用重读，改即生效）；
 * Java 里只有 op 名、kv 键名、工具名、日志前缀与 ASCII 兜底。缺键时明确报错，绝不用隐藏默认值。</p>
 */
public class FileSkill implements Skill {

    private static final String TOOL = "file";
    private static final String NAME = "文件";
    private static final String OPS = "read / list / find / download / write / fetch";

    /**
     * 声明本技能的五个 op（技能管控表就是按这些名字放行或封禁的）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("file", "read", "读文本文件");
        ops.add("file", "list", "列目录");
        ops.add("file", "find", "按关键词找文件");
        ops.add("file", "download", "下载 http/https 到 files/");
        ops.add("file", "write", "写文件");
        ops.add("file", "fetch", "取回对方刚发在 QQ 里的文件");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = Cfg.load(h);
        if (c.bad != null) return c.bad;
        File root = root(h);
        if (root == null) return "[" + TOOL + "] data root unavailable";
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "read";
        try {
            if ("read".equals(op)) return read(a, h, c, root);
            if ("list".equals(op)) return list(a, h, c, root);
            if ("find".equals(op)) return find(a, h, c, root);
            if ("download".equals(op)) return download(a, h, c, root);
            if ("write".equals(op)) return write(a, h, c, root);
            if ("fetch".equals(op)) return fetch(a, h, c, root);
            return c.t("不支持op", "op", op, "ops", OPS);
        } catch (Throwable t) {
            log(h, "op=" + op + " 异常: " + t);
            return c.t("异常", "op", op, "error", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- read

    private static String read(Args a, Host h, Cfg c, File root) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("file.read");
        if (deny != null) return deny;
        String p = a.str("path");
        if (Str.blank(p)) return a.missing("path");
        File f = resolve(root, p);
        if (f == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // >>> W7BC13 (File-domain check; the "before" copy used by the W7 fixture strips this block)
        // ★ W7/批 13（GM 2026-09-26 裁决）：<b>File 域</b>也要问一句 ——
        //   在这之前本技能只判 op（"工具不自己设一道比授权更低的上限"），于是 `file.read` 一旦给
        //   ALLUSER，任何群友就能读本机任意文本文件；而文件管控表的 `File.Read` 只给 `D:/share/`，
        //   那一层却从来没人问（既有活口，见 W7 报告 §9-1）。
        //   现在按"当轮调用者"过一遍 File 域：档位够（favor ≥ favorFileMin）的按好感度档放宽到
        //   `favorFileRoots` 里的允许根，其余人只能碰表里写明的路径；硬 Ban 子树（例
        //   `D:/share/SairFrameWork/`、数据根下的 config.json / v4.db / logs）压过一切。
        //   判定一律问基板（`Host.localFileDeny` → `Acl.fileDeny`），技能不自己写第二套路径规则。
        //   放在"存在性"之前：被拒时连"这个文件存不存在"都不回答。
        String pd = h.localFileDeny(h.subject(), f.getPath());
        if (pd != null) return pd;
        // <<< W7BC13
        if (!f.exists()) return c.t("不存在", "path", rel(root, f));
        if (f.isDirectory()) return c.t("是目录", "path", rel(root, f));
        if (!f.isFile()) return c.t("不是普通文件", "path", rel(root, f));

        long size = f.length();
        // >>> DOCPARSE：文档分支（PDF / Office）。必须在通用「最大读取字节」闸之前 ——
        //     那一道是给纯文本用的（几 MB 足够），而一份正常 PDF/PPT 经常比它大得多。
        //     这里按扩展名分派：PDF 走 pdftotext（零新依赖，poppler 随 Git for Windows 自带）；
        //     docx/xlsx/pptx 是 ZIP+XML，用 JDK 自带的 java.util.zip 解，不需要 PDFBox/POI。
        String ext = extOf(f.getName());
        if (DOC_EXTS.contains(ext)) {
            String d = docRead(a, h, c, root, f, ext, size);
            if (d != null) return d;
        }
        // <<< DOCPARSE

        long maxRead = c.n("最大读取字节");
        if (maxRead <= 0) return c.miss("最大读取字节");
        if (size > maxRead) return c.t("太大", "size", size, "limit", maxRead);

        long defOffset = c.n("默认起始行");
        if (defOffset <= 0) return c.miss("默认起始行");
        int offset = a.i("offset", 0);
        if (offset < 1) offset = (int) defOffset;
        int limit = a.i("limit", 0);
        long defLines = c.n("默认读取行数");
        if (defLines <= 0) return c.miss("默认读取行数");
        if (limit <= 0) limit = (int) defLines;
        long maxLines = c.n("最多返回行数");
        if (maxLines <= 0) return c.miss("最多返回行数");
        if (limit > maxLines) limit = (int) maxLines;

        // 编码自动探测（GBK/GB18030/ANSI 的文本不再整篇乱码）：口径在基板 kit/Text
        sair.v4.kit.Text.Decoded dec = Fs.readDecoded(f);
        if (dec == null) return c.t("读失败", "path", rel(root, f));
        String text = dec.text;
        if (text.indexOf('\0') >= 0) return c.t("是二进制", "path", rel(root, f));
        if (!"UTF-8".equalsIgnoreCase(dec.charset)) {
            log(h, "按 " + dec.charset + (dec.fallback ? "（兜底）" : "") + " 解码：" + rel(root, f));
        }

        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] cut = new String[lines.length - 1];
            System.arraycopy(lines, 0, cut, 0, cut.length);
            lines = cut;
        }
        int total = lines.length;
        if (total == 0) return c.t("空文件", "path", rel(root, f));
        if (offset > total) {
            return c.t("偏移越界", "offset", offset, "path", rel(root, f), "total", total);
        }
        int end = Math.min(total, offset + limit - 1);

        StringBuilder sb = new StringBuilder();
        sb.append(c.t("读头", "path", rel(root, f), "total", total, "from", offset, "to", end)).append('\n');
        int width = String.valueOf(end).length();
        for (int i = offset; i <= end; i++) {
            String line = lines[i - 1];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            StringBuilder no = new StringBuilder(String.valueOf(i));
            while (no.length() < width) no.insert(0, ' ');
            sb.append(c.t("行号", "no", no.toString(), "text", line)).append('\n');
        }
        if (end < total) {
            sb.append(c.t("读还有", "rest", total - end, "next", end + 1));
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- list

    private static String list(Args a, Host h, Cfg c, File root) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("file.list");
        if (deny != null) return deny;
        String p = a.str("path");
        File f = resolve(root, Str.blank(p) ? "." : p);
        if (f == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // >>> W7BC13 (File-domain check; the "before" copy used by the W7 fixture strips this block)
        // ★ W7/批 13（GM 2026-09-26 裁决）：同 `read`，列目录也要过 File 域 —— 否则档位/路径范围
        //   只管住了"读内容"，却把"看目录树"整片漏出去。判定口径与放置位置与 `read` 一致。
        String pd = h.localFileDeny(h.subject(), f.getPath());
        if (pd != null) return pd;
        // <<< W7BC13
        if (!f.exists()) return c.t("路径不存在", "path", rel(root, f));
        if (f.isFile()) return c.t("是文件不是目录", "path", rel(root, f));

        File[] kids = f.listFiles();
        if (kids == null) return c.t("目录读不了", "path", rel(root, f));
        long maxList = c.n("最多列出行数");
        if (maxList <= 0) return c.miss("最多列出行数");

        List<File> all = new ArrayList<File>();
        for (File k : kids) all.add(k);
        Collections.sort(all, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                return x.getName().compareToIgnoreCase(y.getName());
            }
        });
        int limit = a.i("limit", 0);
        if (limit <= 0) limit = (int) maxList;

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("目录头", "path", rel(root, f), "count", all.size())).append('\n');
        int n = 0;
        for (File k : all) {
            if (n >= limit) break;
            n++;
            if (k.isDirectory()) {
                sb.append(c.t("目录项目录", "name", k.getName())).append('\n');
            } else {
                sb.append(c.t("目录项文件", "name", k.getName(), "size", k.length(),
                        "time", df.format(new Date(k.lastModified())))).append('\n');
            }
        }
        if (all.size() > n) sb.append(c.t("目录还有", "rest", all.size() - n));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- find

    private static String find(Args a, Host h, Cfg c, File root) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("file.find");
        if (deny != null) return deny;
        String kw = Str.trim(a.str("keyword"));
        if (kw.isEmpty()) return a.missing("keyword");
        String p = a.str("path");
        File base = resolve(root, Str.blank(p) ? "." : p);
        if (base == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // >>> W7BC13 (File-domain check; the "before" copy used by the W7 fixture strips this block)
        // ★ W7/批 13（GM 2026-09-26 裁决）：扫盘也过 File 域 —— 它既要读文件内容找关键词，
        //   又会把命中的路径名交回去，比 `read` 漏得更多。**两道**：先判用户点名的那个根
        //   （fail fast、且不回答"它存不存在"），再在扫描循环里逐个文件判一次 ——
        //   只判根挡不住"根在允许范围、里面嵌了一棵硬 Ban 子树"（例 `D:/share/` 下面的
        //   `SairFrameWork/`）。
        String pd = h.localFileDeny(h.subject(), base.getPath());
        if (pd != null) return pd;
        // <<< W7BC13
        if (!base.exists()) return c.t("路径不存在", "path", rel(root, base));
        if (base.isFile()) base = base.getParentFile() == null ? base : base.getParentFile();

        long maxHits = c.n("最多命中");
        long maxScanBytes = c.n("最大扫描字节");
        long maxScanFiles = c.n("最多扫描文件");
        long depth = c.n("扫描深度");
        if (maxHits <= 0) return c.miss("最多命中");
        if (maxScanBytes <= 0) return c.miss("最大扫描字节");
        if (maxScanFiles <= 0) return c.miss("最多扫描文件");
        if (depth <= 0) return c.miss("扫描深度");

        List<File> files = Fs.walk(base, null, (int) depth);
        String low = kw.toLowerCase();
        List<String> hits = new ArrayList<String>();
        int scanned = 0;
        for (File f : files) {
            if (scanned++ > maxScanFiles) break;
            if (!f.isFile()) continue;
            // >>> W7BC13 (per-file File-domain check; stripped in the "before" copy)
            // ★ W7/批 13：逐个文件再过一次 File 域（理由见上面那段）。被拒的文件**静默跳过**
            //   （不是整条失败）：扫描本来就是"在允许的树里找"，扫到禁区的枝就绕开 ——
            //   不把禁区的存在、名字、内容以任何形式回给她。
            if (h.localFileDeny(h.subject(), f.getPath()) != null) continue;
            // <<< W7BC13
            boolean nameHit = f.getName().toLowerCase().indexOf(low) >= 0;
            String contentInfo = "";
            if (!nameHit && f.length() > 0 && f.length() <= maxScanBytes) {
                String text = Fs.readAuto(f);           // 扫描也按探测口径解码（GBK 文本同样能命中）
                if (text != null && text.indexOf('\0') < 0) {
                    int at = text.toLowerCase().indexOf(low);
                    if (at >= 0) {
                        int line = 1;
                        for (int i = 0; i < at; i++) if (text.charAt(i) == '\n') line++;
                        int s = Math.max(0, at - 40);
                        int e = Math.min(text.length(), at + kw.length() + 40);
                        contentInfo = c.t("命中内容", "path", rel(root, f), "line", line,
                                "snippet", Str.oneLine(text.substring(s, e)));
                    }
                }
            }
            if (!nameHit && contentInfo.isEmpty()) continue;
            hits.add(nameHit ? c.t("命中文件名", "path", rel(root, f)) : contentInfo);
            if (hits.size() >= maxHits) break;
        }
        int done = (int) Math.min(scanned, maxScanFiles);
        if (hits.isEmpty()) {
            return c.t("查找无果", "path", rel(root, base), "scanned", done, "keyword", kw);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("查找头", "path", rel(root, base), "count", hits.size(),
                "keyword", kw, "scanned", done)).append('\n');
        for (String s : hits) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- download

    private static String download(Args a, Host h, Cfg c, File root) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— 出网下载是这一个 op 的事
        String deny = h.need("file.download");
        if (deny != null) return deny;

        String url = Str.trim(a.str("url"));
        if (url.isEmpty()) return a.missing("url");
        String low = url.toLowerCase();
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            return c.t("下载地址非法", "url", Str.cut(url, 200));
        }
        long limit = c.n("下载上限字节");
        long timeout = c.n("下载超时毫秒");
        if (limit <= 0) return c.miss("下载上限字节");
        if (timeout <= 0) return c.miss("下载超时毫秒");

        File base = filesDir(h, c);
        if (base == null) return "[" + TOOL + "] files dir unavailable";
        String wanted = Str.trim(a.str("path"));
        if (wanted.isEmpty()) wanted = nameOf(url, c);
        File dst = resolveIn(base, wanted);
        if (dst == null) return c.t("下载越界", "path", Str.cut(Str.nz(wanted), 160));
        // 这是个**结构性**约束（落点必须在 files\ 里），不是权限；能不能下载已由上面的 op 判定过了。

        Fs.mkdirs(base);
        log(h, c.t("下载前", "url", Str.cut(url, 200), "path", rel(root, dst), "limit", limit));
        // 带 SSRF 闸门的下载：每一跳重定向都过基板 kit/NetGuard（被拒时 dl.blocked 非空）
        Http.Dl dl = Http.download(url, dst, (int) timeout);
        if (dl.denied()) {
            return c.t("下载拒绝内网", "url", Str.cut(url, 200), "host", dl.blockedHost,
                    "reason", dl.blockedReason, "code", dl.blocked);
        }
        if (dl.redirectBroken()) {
            return c.t("下载跳转异常", "url", Str.cut(dl.finalUrl.isEmpty() ? url : dl.finalUrl, 200),
                    "reason", dl.redirect);
        }
        if (!dl.ok()) return c.t("下载失败", "url", Str.cut(url, 200), "error", dl.error);

        long size = Fs.size(dst);
        if (size > limit) {
            Fs.deleteRec(dst);
            return c.t("下载超限", "size", size, "limit", limit, "path", rel(root, dst));
        }
        return c.t("下载成功", "path", rel(root, dst), "size", size, "url", Str.cut(url, 200));
    }

    // ---------------------------------------------------------------- write

    private static String write(Args a, Host h, Cfg c, File root) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("file.write");
        if (deny != null) return deny;
        String p = a.str("path");
        if (Str.blank(p)) return a.missing("path");
        if (!a.has("content")) return a.missing("content");
        String content = Str.nz(a.str("content"));
        long limit = c.n("写入上限字节");
        if (limit <= 0) return c.miss("写入上限字节");

        long size = content.getBytes(Fs.UTF8).length;
        if (size > limit) return c.t("写入超限", "size", size, "limit", limit);

        File f = resolve(root, p);
        if (f == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // >>> W15D1
        // ★ W15/批 15（2026-09-26，甲方票据：`file.write` 没有路径上限）：批 13 只给
        //   read / list / find 补了下面那道 File 域判定（见上面三处 `W7BC13` 块），**write 漏了** ——
        //   于是 `file.write` 一旦给 ALLUSER，拿到这个 op 的人就能往本机任意可写位置落文件：
        //   绝对路径（例 `%TEMP%\x\pwned.txt`）与"数据根相对 + `..` 逃逸"（`../outside.txt`）
        //   都能落地，硬 Ban 子树（数据根下的 config.json / v4.db / logs、`D:/share/SairFrameWork/`）
        //   也照写不误 —— 而同一个调用者用 read 读同一棵树是会被拒的（口径不一致）。
        //   实测落点见夹具 `tmp\v6-real\batch6\W15d`（改前真被写到数据根外面）。
        //   改法与批 13 逐条一样：**问基板**（`Host.localFileDeny` → `Acl.fileDeny`），技能不自己写
        //   第二套路径规则。放在 `resolve` 之后、动手之前：被判死时连"这个落点存不存在"都不回答，
        //   更不会落盘。授权仍由分支开头那一判 `h.need("file.write")` 说了算，这里一个字没放宽；
        //   主人 / 她本人（`Acl.isFree`）恒 null ⇒ 逐字节不变。
        //   注：`Host.localFileDeny` 交出去的是 **Read** 口径（`acl.fileDeny(..., false)`）——
        //   本车道只拥有技能这两个文件，写侧口径若要单开一路，那是基板出口的事（不在本文件里改）。
        String wd = h.localFileDeny(h.subject(), f.getPath());
        if (wd != null) return wd;
        // 落点的上限现在是上面那道 File 域判定（W15D1）；改之前这里写的是"能写哪儿由技能管控表判
        // （技能不设'必须落在数据根以内'的上限）"—— 那句话对 read 早就被批 13 推翻了，对 write 也在批 15 推翻。
        // <<< W15D1
        if (f.exists() && f.isDirectory()) return c.t("写入是目录", "path", rel(root, f));

        long old = f.isFile() ? f.length() : 0L;
        // 写前报字节数（进控制台日志），落盘前就把"要写多少"讲清楚
        log(h, c.t("写入前", "path", rel(root, f), "size", size, "old", old));
        if (!Fs.write(f, content)) return c.t("写入失败", "path", rel(root, f));

        int lines = 1;
        for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') lines++;
        if (content.isEmpty()) lines = 0;
        return c.t("写入成功", "path", rel(root, f), "size", Fs.size(f), "lines", lines, "old", old);
    }

    // ---------------------------------------------------------------- 路径解析（结构性，不是权限边界）

    private static File root(Host h) {
        try {
            if (h == null || h.conf() == null || h.conf().root() == null) return null;
            File r = real(h.conf().root().getCanonicalFile());
            return r == null ? h.conf().root().getCanonicalFile() : r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File filesDir(Host h, Cfg c) {
        try {
            if (h == null || h.conf() == null || h.conf().filesDir() == null) return null;
            File d = h.conf().filesDir().getCanonicalFile();
            File r = real(d);
            return r == null ? d : r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析路径：<b>相对路径按 {@code base} 解析</b>（"相对于谁"的语义），<b>绝对路径原样采用</b>。
     *
     * <p><b>这里不做"必须在某个根以内"的限定</b> —— 能不能碰这个文件由 op 判（动作分支开头的那一句
     * {@code h.need("file.read")} 等），工具不自己设一道比授权更低的上限：主人要读数据根以外的
     * 文件（例如 {@code D:\Share\**\x.mp3}）时，挡他的只能是管控表，不是技能。
     * 落点固定在 {@code files/} 的 {@code download} 另走 {@link #resolveIn}。</p>
     *
     * <p><b>两道结构性解析照旧（不是权限）</b>：① {@code getCanonicalFile()} 把 {@code ..} 与符号链接抵掉；
     * ② {@code toRealPath()} 把 Windows 目录联接（junction）也解成真实位置 —— 别名（软链 / junction）
     * 骗不过这两道。
     * 写法系统不接受（无法规范化）时返回 null：调用方把这句当**输入不合法**明确报出，不猜、也不当权限拒绝。</p>
     */
    private static File resolve(File base, String path) {
        String p = Str.trim(path).replace('\\', '/');
        if (p.isEmpty()) p = ".";
        try {
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(base, p);
            File canon = f.getCanonicalFile();      // ① `..` / 符号链接：规范化
            File r = real(canon);                   // ② junction / 符号链接：解成真实位置
            return r == null ? canon : r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 落点限定版（{@code download} 专用）：相对 {@code base}（= {@code h.conf().filesDir()}）解析，
     * 且 <b>① 规范化路径</b>与 <b>② 真实位置</b>都必须仍落在 {@code base} 以内，否则返回 null。
     *
     * <p>这不是权限，是这个 op 的<b>语义</b>：下载的落点固定是数据根的 {@code files/}（回绝文案见 prompt.md
     * 的「下载越界」）；能不能下载已由分支开头的 {@code h.need("file.download")} 判过。
     * 两道前缀校验同时留着，才能挡住 {@code ../} 与"指向根外的 junction"两种逃逸。</p>
     */
    private static File resolveIn(File base, String path) {
        String p = Str.trim(path).replace('\\', '/');
        if (p.isEmpty()) p = ".";
        try {
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(base, p);
            File canon = f.getCanonicalFile();
            if (!inside(base, canon)) return null;              // ① 规范化之后必须在 base 内
            File r = real(canon);
            if (r == null || !inside(base, r)) return null;      // ② 真实位置也必须在 base 内
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 真实位置：把"最深一层已存在的祖先"用 {@code toRealPath()} 解开（junction / 符号链接都在这里露出来），
     * 再把还不存在的尾巴拼回去。失败返回 null —— 调用方对 {@link #resolve} 退回规范化路径（不因此拒绝），
     * 对 {@link #resolveIn} 才算"落点不可信"。真身化只是结构性防护，不是权限判定本身。
     */
    private static File real(File f) {
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

    private static boolean inside(File base, File f) {
        String r = base.getAbsolutePath();
        String c = f.getAbsolutePath();
        if (c.equalsIgnoreCase(r)) return true;
        String rs = r.endsWith(File.separator) ? r : r + File.separator;
        return c.regionMatches(true, 0, rs, 0, rs.length());
    }

    private static String rel(File root, File f) {
        String r = root.getAbsolutePath();
        String c = f.getAbsolutePath();
        if (c.equalsIgnoreCase(r)) return ".";
        String rs = r.endsWith(File.separator) ? r : r + File.separator;
        return c.regionMatches(true, 0, rs, 0, rs.length()) ? c.substring(rs.length()).replace('\\', '/') : c;
    }

    /** URL → 文件名（取不到就用 prompt.md 的 默认下载名）。 */
    private static String nameOf(String url, Cfg c) {
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : "";
        name = Str.safeName(name);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = Str.nz(c.s("默认下载名"));
        return name;
    }

    private static void log(Host h, String msg) {
        try {
            if (h == null) return;
            Out o = h.out();
            if (o != null) o.dim("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- prompt.md

    // ---------------------------------------------------------------- 文档解析（PDF / Office）

    /** 走文档分支的扩展名。旧版 doc/xls/ppt 是 OLE2 二进制，单独给一句人话。 */
    static final java.util.Set<String> DOC_EXTS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "pdf", "docx", "xlsx", "xlsm", "pptx", "doc", "xls", "ppt"));

    static String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    // ---------------------------------------------------------------- fetch（取回对方刚发的文件）

    /**
     * 取回入站文件：从基板的入站登记簿定位文件段 → 换成可下载地址 → <b>复用 download</b> 落进 files/。
     *
     * <p>为什么要分两步：图片段自带直链，而 {@code file} 段一般只有一个 {@code file} 值，
     * 内容要拿它去换（{@code get_file}）。换地址这条路基板已经封装在
     * {@code Api.refreshImageUrlQuiet}（rkey → get_image → get_file → get_msg 四级降级，全程吞错），
     * 技能不自己发 NapCat 动作，也不自己判 SSRF —— 下载那一步仍走 download 的 NetGuard 闸门。</p>
     */
    private static String fetch(Args a, Host h, Cfg c, File root) {
        String deny = h.need("file.fetch");
        if (deny != null) return deny;
        long keep = c.n("取文件保留条数");
        if (keep <= 0) return c.miss("取文件保留条数");
        List<JsonObject> list = null;
        try {
            list = h.inboundFiles((int) keep);
        } catch (Throwable ignored) {
        }
        if (list == null || list.isEmpty()) return c.t("取文件无", "count", 0);

        String want = Str.trim(a.str("name"));
        JsonObject pick = matchFile(list, want);
        if (pick == null) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < list.size() && i < 8; i++) {
                String nm = Str.trim(J.s(list.get(i), "name", ""));
                if (nm.isEmpty()) nm = Str.trim(J.s(list.get(i), "file", ""));
                if (nm.isEmpty()) continue;
                if (names.length() > 0) names.append("、");
                names.append(Str.cut(nm, 40));
            }
            return c.t("取文件没匹配", "want", Str.cut(Str.nz(want), 80), "names", names.toString());
        }

        String file = Str.trim(J.s(pick, "file", ""));
        String name = Str.trim(J.s(pick, "name", ""));
        String url = "";
        try {
            sair.v4.qq.Api api = h.napcat();
            if (api != null) url = api.refreshImageUrlQuiet("", file, 0L);
        } catch (Throwable ignored) {
        }
        if (!Str.has(url)) {
            return c.t("取文件无地址", "name", Str.cut(Str.nz(name), 80), "file", Str.cut(Str.nz(file), 80));
        }

        String stamp = new java.text.SimpleDateFormat("HHmmss").format(new java.util.Date());
        String safe = safeName(name.isEmpty() ? file : name, "recv-" + stamp + ".bin");
        JsonObject q = new JsonObject();
        q.addProperty("url", url);
        q.addProperty("path", safe);
        String got = download(new Args("file", q), h, c, root);

        // 文档顺手解析一次（省一轮调用）：只对 pdf/docx/xlsx/pptx 生效，parse=0 可关
        try {
            File dir2 = filesDir(h, c);
            File landed = dir2 == null ? null : new File(dir2, safe);
            if (landed != null && landed.isFile() && landed.length() > 0
                    && !"0".equals(Str.trim(a.str("parse")))) {
                String ext = extOf(landed.getName());
                if (DOC_EXTS.contains(ext)) {
                    String body = docRead(a, h, c, root, landed, ext, landed.length());
                    if (body != null) got = got + "\n\n" + body;
                }
            }
        } catch (Throwable ignored) {
        }
        return got;
    }

    /**
     * 在入站文件里挑一个（纯函数，探针可反射测）：空的 {@code want} = 取最新那条；
     * 否则按「名字全等 → 名字包含 → file 值全等 → file 值包含」依次匹配。
     */
    static JsonObject matchFile(List<JsonObject> list, String want) {
        if (list == null || list.isEmpty()) return null;
        String w = Str.trim(want);
        if (w.isEmpty()) return list.get(0);
        String lw = Str.lower(w);
        for (JsonObject o : list) if (lw.equals(Str.lower(Str.trim(J.s(o, "name", ""))))) return o;
        for (JsonObject o : list) if (Str.lower(Str.trim(J.s(o, "name", ""))).contains(lw)) return o;
        for (JsonObject o : list) if (lw.equals(Str.lower(Str.trim(J.s(o, "file", ""))))) return o;
        for (JsonObject o : list) if (Str.lower(Str.trim(J.s(o, "file", ""))).contains(lw)) return o;
        return null;
    }

    /** 把入站里的显示名洗成能安全落盘的裸文件名（去目录、替换 Windows 非法字符）。 */
    static String safeName(String name, String fallback) {
        String n = Str.trim(name);
        int i = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (i >= 0) n = n.substring(i + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        n = n.trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) n = fallback;
        if (n.length() > 120) {
            String ext = extOf(n);
            String keepExt = ext.isEmpty() ? "" : ("." + ext);
            n = n.substring(0, Math.min(120 - keepExt.length(), n.length())) + keepExt;
        }
        return n;
    }
    /**
     * 文档读取（PDF / Office）：成功返回给模型的正文；返回 null = 不归我管。
     *
     * <p>零新依赖的做法：PDF 交给 {@code pdftotext}（poppler，随 Git for Windows 自带，参数数组启动、不过 shell）；
     * docx/xlsx/pptx 本质是 ZIP+XML，用 JDK 自带的 {@link ZipFile} 解开按标签取文本 —— 不需要 PDFBox/POI。</p>
     */
    static String docRead(Args a, Host h, Cfg c, File root, File f, String ext, long size) {
        long docMax = c.n("文档最大字节");
        if (docMax <= 0) return c.miss("文档最大字节");
        if (size > docMax) return c.t("文档太大", "path", rel(root, f), "size", size, "limit", docMax);
        long dcap = c.n("文档最长字符");
        if (dcap <= 0) return c.miss("文档最长字符");
        int want = a.i("max", 0);
        if (want <= 0) want = (int) Math.min(8000L, dcap);
        if (want > dcap) want = (int) dcap;

        if ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext)) {
            return c.t("旧版office", "path", rel(root, f), "ext", ext);
        }

        String body = null;
        int pages = 0;
        try {
            if ("pdf".equals(ext)) {
                String tool = Str.trim(Str.nz(c.s("pdftotext")));
                if (tool.isEmpty()) return c.miss("pdftotext");
                PdfOut po = pdfText(f, tool, a.i("from", 0), a.i("to", 0), 120000L);
                if (po == null) return c.t("pdf工具缺失", "tool", tool);
                body = po.text;
                pages = po.pages;
                if (!Str.has(Str.trim(body))) {
                    // 抽不出东西时把 pdftotext 的口径带出来（退出码 / stderr），否则无从排障
                    String why = po.code == 0 ? "" : ("，exit=" + po.code);
                    if (Str.has(po.err)) why = why + "，" + po.err;
                    return c.t("pdf无文字层", "path", rel(root, f), "pages", pages, "why", why);
                }
            } else if ("docx".equals(ext)) {
                body = docxText(f);
            } else if ("xlsx".equals(ext) || "xlsm".equals(ext)) {
                body = xlsxText(f);
            } else if ("pptx".equals(ext)) {
                body = pptxText(f);
            }
        } catch (Throwable t) {
            return c.t("文档解析失败", "path", rel(root, f), "error", String.valueOf(t));
        }
        if (body == null) return c.t("文档类型不支持", "path", rel(root, f), "ext", ext);
        body = tidy(body);
        if (body.isEmpty()) return c.t("文档空", "path", rel(root, f));
        String pageInfo = pages > 0 ? ("，共 " + pages + " 页") : "";
        String head = c.t("文档读头", "path", rel(root, f), "type", ext, "chars", body.length(), "pages", pageInfo);
        if (body.length() > want) {
            return head + "\n" + body.substring(0, want) + "\n"
                    + c.t("文档截断", "rest", body.length() - want, "total", body.length());
        }
        return head + "\n" + body;
    }

    static final class PdfOut {
        String text = "";
        int pages;
        String err = "";
        int code;
    }

    /** 调 pdftotext 抽文本。返回 null = 工具拉不起来（调用方报「pdf工具缺失」）。 */
    static PdfOut pdfText(File f, String tool, int from, int to, long timeoutMs) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(tool);
        cmd.add("-layout");
        cmd.add("-enc");
        cmd.add("UTF-8");
        if (from > 0) { cmd.add("-f"); cmd.add(String.valueOf(from)); }
        if (to > 0) { cmd.add("-l"); cmd.add(String.valueOf(to)); }
        cmd.add(f.getAbsolutePath());
        cmd.add("-");
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (java.io.IOException ioe) {
            return null;
        }
        StringBuilder o = new StringBuilder();
        StringBuilder e = new StringBuilder();
        Thread t1 = drain(p.getInputStream(), o);
        Thread t2 = drain(p.getErrorStream(), e);
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new java.io.IOException("pdftotext 超时");
        }
        t1.join(3000L);
        t2.join(3000L);
        String raw = o.toString();
        PdfOut out = new PdfOut();
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) == '\f') out.pages++;
        if (out.pages == 0 && raw.trim().length() > 0) out.pages = 1;
        out.text = raw.replace("\f", "\n");
        out.err = Str.cut(Str.oneLine(e.toString()), 200);
        out.code = p.exitValue();
        return out;
    }

    static Thread drain(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.InputStreamReader r = new java.io.InputStreamReader(in, "UTF-8");
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 读 ZIP 里某个 entry 的 UTF-8 文本；不存在返回 null。 */
    static String zipEntry(File f, String name) {
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            ZipEntry en = z.getEntry(name);
            if (en == null) return null;
            InputStream in = z.getInputStream(en);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (z != null) { try { z.close(); } catch (Throwable ignored) { } }
        }
    }

    /** ZIP 里以某前缀开头的 entry，按结尾数字排序（sheet2 在 sheet10 之前）。 */
    static List<String> zipNames(File f, String prefix) {
        List<String> out = new ArrayList<String>();
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String nm = en.nextElement().getName();
                if (nm.startsWith(prefix) && nm.endsWith(".xml")) out.add(nm);
            }
        } catch (Throwable ignored) {
        } finally {
            if (z != null) { try { z.close(); } catch (Throwable ignored) { } }
        }
        final java.util.Map<String, Integer> num = new java.util.HashMap<String, Integer>();
        for (String nm : out) {
            Matcher m = Pattern.compile("(\\d+)").matcher(nm.substring(prefix.length()));
            num.put(nm, m.find() ? Integer.valueOf(m.group(1)) : Integer.valueOf(0));
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return num.get(x).compareTo(num.get(y));
            }
        });
        return out;
    }

    /** docx：word/document.xml 里 <w:t> 的文本；段落/表格/换行转成换行或制表。 */
    static String docxText(File f) {
        String xml = zipEntry(f, "word/document.xml");
        if (xml == null) return null;
        String s = xml;
        s = s.replaceAll("(?is)<w:tab[^>]*/>", "\t");
        s = s.replaceAll("(?is)<w:br[^>]*/>|</w:p>|</w:tr>", "\n");
        s = s.replaceAll("(?is)</w:tc>", "\t");
        s = s.replaceAll("(?is)<[^>]+>", "");
        return unescapeXml(s);
    }

    /** xlsx：共享字符串表 + 每张工作表逐行逐格拼成制表符文本。 */
    static String xlsxText(File f) {
        List<String> shared = new ArrayList<String>();
        String ss = zipEntry(f, "xl/sharedStrings.xml");
        if (ss != null) {
            Matcher m = Pattern.compile("(?is)<si\\b[^>]*>(.*?)</si>").matcher(ss);
            while (m.find()) shared.add(unescapeXml(m.group(1).replaceAll("(?is)<[^>]+>", "")));
        }
        List<String> names = new ArrayList<String>();
        String wb = zipEntry(f, "xl/workbook.xml");
        if (wb != null) {
            Matcher m = Pattern.compile("(?is)<sheet\\b[^>]*name=\"([^\"]*)\"").matcher(wb);
            while (m.find()) names.add(unescapeXml(m.group(1)));
        }
        List<String> sheets = zipNames(f, "xl/worksheets/sheet");
        if (sheets.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            String xml = zipEntry(f, sheets.get(i));
            if (xml == null) continue;
            String title = i < names.size() ? names.get(i) : ("sheet" + (i + 1));
            sb.append("### ").append(title).append('\n');
            Matcher rows = Pattern.compile("(?is)<row\\b[^>]*>(.*?)</row>").matcher(xml);
            while (rows.find()) {
                List<String> cells = new ArrayList<String>();
                Matcher cs = Pattern.compile("(?is)<c\\b([^>]*?)(?:/>|>(.*?)</c>)").matcher(rows.group(1));
                while (cs.find()) {
                    String attrs = cs.group(1) == null ? "" : cs.group(1);
                    String body = cs.group(2) == null ? "" : cs.group(2);
                    String ty = attr(attrs, "t");
                    String val;
                    if ("s".equals(ty)) {
                        String v = inner(body, "v");
                        int idx = -1;
                        try { idx = Integer.parseInt(Str.trim(v)); } catch (Throwable ignored) { }
                        val = (idx >= 0 && idx < shared.size()) ? shared.get(idx) : "";
                    } else if ("inlineStr".equals(ty)) {
                        val = unescapeXml(inner(body, "t").replaceAll("(?is)<[^>]+>", ""));
                    } else {
                        val = unescapeXml(inner(body, "v"));
                    }
                    cells.add(Str.nz(val).replace('\n', ' ').trim());
                }
                sb.append(Str.join(cells, "\t")).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** pptx：按页取 <a:t> 文本，段落一行。 */
    static String pptxText(File f) {
        List<String> slides = zipNames(f, "ppt/slides/slide");
        if (slides.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slides.size(); i++) {
            String xml = zipEntry(f, slides.get(i));
            if (xml == null) continue;
            sb.append("--- 第 ").append(i + 1).append(" 页 ---\n");
            Matcher ps = Pattern.compile("(?is)<a:p\\b[^>]*>(.*?)</a:p>").matcher(xml);
            while (ps.find()) {
                StringBuilder line = new StringBuilder();
                Matcher ts = Pattern.compile("(?is)<a:t>(.*?)</a:t>").matcher(ps.group(1));
                while (ts.find()) line.append(unescapeXml(ts.group(1)));
                String t = line.toString().trim();
                if (!t.isEmpty()) sb.append(t).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs == null ? "" : attrs);
        return m.find() ? m.group(1) : "";
    }

    static String inner(String xml, String tag) {
        Matcher m = Pattern.compile("(?is)<" + Pattern.quote(tag) + "\\b[^>]*>(.*?)</" + Pattern.quote(tag) + ">").matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1) : "";
    }

    static String unescapeXml(String s) {
        if (s == null) return "";
        String t = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = m.group(0);
            try {
                int cp = m.group(1).isEmpty() ? Integer.parseInt(m.group(2)) : Integer.parseInt(m.group(2), 16);
                rep = new String(Character.toChars(cp));
            } catch (Throwable ignored) {
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 压掉行尾空白与连续空行。 */
    static String tidy(String s) {
        if (s == null) return "";
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String t = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (t.isEmpty()) {
                blank++;
                if (blank > 1) continue;
            } else {
                blank = 0;
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    /** prompt.md 里的配置与文案（每次调用重读）。 */
    private static final class Cfg {

        private final Map<String, String> v = new LinkedHashMap<String, String>();
        private String bad;

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            File dir = null;
            try {
                if (h != null) {
                    dir = h.skillDir();
                    if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
                }
            } catch (Throwable ignored) {
            }
            String text = dir == null ? null : Fs.read(new File(dir, "prompt.md"), null);
            if (text == null) {
                c.bad = "[" + TOOL + "] prompt.md unreadable under "
                        + (dir == null ? "?" : dir.getAbsolutePath());
                return c;
            }
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int i = line.indexOf(':');
                int j = line.indexOf('：');
                if (i < 0 || (j >= 0 && j < i)) i = j;
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                if (!k.isEmpty()) c.v.put(k, unesc(line.substring(i + 1).trim()));
            }
            return c;
        }

        String s(String k) { return v.get(k); }

        /** 数值配置；缺键或非正数返回 -1（调用方会明确报缺键，而不是用隐藏默认值）。 */
        long n(String k) {
            String s = v.get(k);
            if (s == null) return -1L;
            try {
                long n = Long.parseLong(s.trim());
                return n > 0 ? n : -1L;
            } catch (Exception e) {
                return -1L;
            }
        }

        String miss(String k) { return "[" + TOOL + "] prompt.md missing config: " + k; }

        String t(String k, Object... kv) {
            String tpl = v.get(k);
            if (tpl == null) return "[" + TOOL + "] prompt.md missing text: " + k;
            return fill(tpl, kv);
        }

        private static String fill(String tpl, Object... kv) {
            String s = tpl;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
            }
            return s;
        }

        private static String unesc(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t");
        }
    }
}
