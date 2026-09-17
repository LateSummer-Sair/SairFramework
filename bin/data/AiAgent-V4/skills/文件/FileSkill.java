import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.Http;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 文件（工具 {@code file}）：read / list / find / download / write。
 *
 * <p><b>能碰什么：唯一权威是 ACL，技能不给自己设一道更低的上限</b>。相对路径按 {@code h.conf().root()}
 * 解析（那是"相对于谁"的语义，保留），绝对路径原样采用 —— 主人要读数据根以外的 A 类文件
 * （例如 {@code D:\Share\**\x.mp3}）时，挡他的只有账本，不是技能；只有把这道自设的锁拿掉，账本给 A 类资源
 * 写的例外（{@code A["User…","D:/share","R"]}）才真有工具用得上，而不是"死授权"。
 * 能不能碰，由调用点的 {@code h.needPath(f, 'R'/'W')} 判：<b>被拒时把 ACL 的拒绝原文原样返回给调用方</b>
 * （形如 {@code 缺 R 位 —— [权限阻断] …}）—— 调用方要靠这句话决定要不要升级到更重的工具，
 * 技能另造一句拒绝就会把它带偏。口径：从最低权限起、不行再逐步升级（MASTER 到 MASTER、SYSTEM 到 SYSTEM、
 * ALLUSER 的上限由例外清单决定）。</p>
 *
 * <p><b>结构性防护仍在（这些不是权限，别删）</b>：① {@code getCanonicalFile()} 抵掉 {@code ..} 与符号链接；
 * ② 再用 {@code toRealPath()} 把<b>最深一层已存在的祖先</b>解成真实位置 —— Windows 上
 * {@code getCanonicalPath()} 不解析目录联接（junction），只做 ① 会让 {@code files/联接/secret.txt} 静默指到
 * 数据根外面；② 让 junction 与符号链接一起露出来，而且对"还不存在的新文件"（write / download 的落点）同样成立。
 * 两者留在 {@link #resolve} 里是让 ACL 判<b>真身</b>：别名（软链 / junction）换不来资源类别。
 * {@code download} 的落点固定在数据根的 {@code files/}（{@code h.conf().filesDir()}）—— 那是这个 op 的语义，
 * 见 {@link #resolveIn}，两道前缀校验在那儿照旧生效；{@code find} / {@code list} 的扫描条数与深度上限也照旧。</p>
 *
 * <p><b>零硬编码文案</b>：阈值与全部面向模型/用户的文本都读自同目录 {@code prompt.md}（每次调用重读，改即生效）；
 * Java 里只有 op 名、kv 键名、工具名、日志前缀与 ASCII 兜底。缺键时明确报错，绝不用隐藏默认值。</p>
 */
public class FileSkill implements Skill {

    private static final String TOOL = "file";
    private static final String NAME = "文件";
    private static final String OPS = "read / list / find / download / write";

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
            return c.t("不支持op", "op", op, "ops", OPS);
        } catch (Throwable t) {
            log(h, "op=" + op + " 异常: " + t);
            return c.t("异常", "op", op, "error", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- read

    private static String read(Args a, Host h, Cfg c, File root) {
        String p = a.str("path");
        if (Str.blank(p)) return a.missing("path");
        File f = resolve(root, p);
        if (f == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // 资源判定（ACL）：能碰什么由账本判，技能不设"必须在数据根以内"这种上限；
        // 判的是这段路径**实际落在哪一类**：数据根的 files\ 目录 = C 类（她自己的运行时脚手架，
        // 见 Res.dataDir）且带"files 树"标记 ⇒ files\ 树属 C、无专有位 ⇒ C:非主人只读（R）；
        // files\ 以外、SFW 根以内 = B 类（ALLUSER 在 B 类无位 = ∅）；
        // 数据根以外的本机文件 = A 类（ALLUSER 默认一位都不给，要开只能靠例外条目；主人恒全权）。
        String deny = h == null ? null : h.needPath(f, 'R');
        if (deny != null) return deny;
        if (!f.exists()) return c.t("不存在", "path", rel(root, f));
        if (f.isDirectory()) return c.t("是目录", "path", rel(root, f));
        if (!f.isFile()) return c.t("不是普通文件", "path", rel(root, f));

        long size = f.length();
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
        String p = a.str("path");
        File f = resolve(root, Str.blank(p) ? "." : p);
        if (f == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        String deny = h == null ? null : h.needPath(f, 'R');    // 列目录 = 读这个目录（能不能列全由 ACL 判：files\ 树属 C、无专有位 ⇒ C:非主人只读（R）；别处 B:非主人默认无位；数据根外 A:非主人默认无位）
        if (deny != null) return deny;
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
        String kw = Str.trim(a.str("keyword"));
        if (kw.isEmpty()) return a.missing("keyword");
        String p = a.str("path");
        File base = resolve(root, Str.blank(p) ? "." : p);
        if (base == null) return c.t("路径非法", "path", Str.cut(Str.nz(p), 160));
        // 递归扫这一棵子树并按内容匹配 = 拿这棵子树下的文件当语料 → 判这棵子树的读位（唯一权威是 ACL：
        // 数据根内 files\ 属 C、无专有位 ⇒ 非主人只读（R）也读得到；别处 B:非主人无位；数据根外 A 类默认无位）
        String deny = h == null ? null : h.needPath(base, 'R');
        if (deny != null) return deny;
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
        // 出网 = 执行一把工具 → 判这个工具的 X 位（工具属 T 类，只判入口）。
        // 这里是**T 类入口**（`Res.tool(...)`，不是 files 树）⇒ ALLUSER 默认 T=无 ⇒ 非主人一把工具都用不了
        // （要特例放行；不是门槛，只是可被账本例外收回的位判定）。
        String denyTool = h.need(sair.v4.auth.Res.tool("file.download"), 'X');
        if (denyTool != null) return denyTool;

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
        // 落点固定在数据根的 files\ 目录 = C 类（她自己的运行时脚手架，D22）⇒ files\ 树属 C、无专有位
        // ⇒ 非主人只读（R），**非主人写不进**。
        // 上面那句"越界"是这个 op 的**结构性**语义（落点必须在 files\ 里），不是权限；能不能写由 ACL 判：
        String deny = h.needPath(dst, 'W');
        if (deny != null) return deny;

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
        // 能写哪儿由 ACL 判（技能不设"必须落在数据根以内"的上限），判的是这段路径**实际落在哪一类**：
        // ① 落在数据根的 files\ 目录 = C 类（她自己的运行时脚手架）⇒ files\ 树属 C、无专有位 ⇒
        //    非主人只读（R），**非主人写不进**；
        // ② 数据根里的技能目录（skills\**，含技能源码与 prompt.md）是 B 类（对非主人无位 = ∅），而
        //    prompts\**（第二层）与 perms.json 这类账本（第一层）连例外都不开 ⇒ 非主人照样写不进；
        // ③ 数据根以外 = A 类（ALLUSER 默认一位都不给）⇒ 主人照常写得进、非主人要靠例外清单 ——
        //    这正是"工具不自己设一道比 ACL 更低的上限"的后果。
        String deny = h.needPath(f, 'W');
        if (deny != null) return deny;
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
     * <p><b>这里不做"必须在某个根以内"的限定</b> —— 能不能碰这个文件由 ACL 判（调用点的
     * {@code h.needPath(f, 'R'/'W')}），工具不自己设一道比 ACL 更低的上限：主人要读数据根以外的
     * A 类文件（例如 {@code D:\Share\**\x.mp3}）时，挡他的只能是账本，不是技能；也因此账本给 A 类
     * 写的例外条目才真有工具用得上。落点固定在 {@code files/} 的 {@code download} 另走 {@link #resolveIn}。</p>
     *
     * <p><b>两道结构性解析照旧（不是权限）</b>：① {@code getCanonicalFile()} 把 {@code ..} 与符号链接抵掉；
     * ② {@code toRealPath()} 把 Windows 目录联接（junction）也解成真实位置 —— 于是 ACL 判的是<b>真身</b>：
     * 别名换不来资源类别（{@code files/联接/secret.txt} 不会被当成 {@code files/} 里的 C 类）。
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
     * 的「下载越界」）；能不能往 {@code files/} 写，仍由调用点的 {@code needPath(dst, 'W')} 判。
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
     * 对 {@link #resolveIn} 才算"落点不可信"。真身化只为让 ACL 判真身，不是权限判定本身。
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
