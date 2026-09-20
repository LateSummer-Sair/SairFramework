import com.google.gson.JsonObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.imageio.ImageIO;

import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 批量处理（工具 {@code batch}）：<b>危险 op</b> —— 正则批量改名、图片批量转格式。
 *
 * <p>四条硬约束（不是建议，是代码）：<br>
 * ① <b>只动数据目录以内</b>：相对路径按 {@code h.conf().root()} 解析，一律先 {@code getCanonicalFile()}
 * 再比前缀，{@code ..}、绝对路径、符号链接逃逸一律<b>明确拒绝</b>；<br>
 * ② <b>动作前先报数量</b>：先把候选清单扫出来、把数量与预览报出来，再谈动不动手；<br>
 * ③ <b>默认试运行</b>（{@code dry_run} 缺省 = {@code prompt.md} 的「默认试运行」= true）：不显式给
 * {@code dry_run:false} 就一个字节都不落盘；<br>
 * ④ <b>能不能改，按 op 判</b>：{@code rename} 判 {@code batch.rename}、{@code convert} 判
 * {@code batch.convert} —— 每个动作分支开头各判一次（{@link #rename} / {@link #convert}），
 * 判不过就把拒绝原文原样回给调用方、一个字节都不动。授权与否由主人写技能管控表
 * （{@code data/skillctl.json}）：op 放行才动手。</p>
 *
 * <p>改名只动"主名"（扩展名保留），替换串支持 {@code $1} 这类分组引用；目标已存在时跳过（不覆盖）。
 * 转格式走 Java2D/ImageIO：目标格式要既在 {@code prompt.md} 的白名单里、本机又真的能写；
 * 写 jpg/bmp 时先按「背景色」压平成不透明图（否则透明区域会变黑）。</p>
 */
public class BatchSkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），也是日志前缀。 */
    private static final String NAME = "批量处理";
    /** 允许格式的结构性兜底。 */
    private static final String ALLOW_DEF = "png,jpg,jpeg,gif,bmp";

    // ================================================================ 工具入口

    /**
     * 声明本技能的两个 op（技能管控表就是按这些名字放行或封禁的）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("batch", "rename", "正则批量改名");
        ops.add("batch", "convert", "图片批量转格式");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        if (h == null) return msg(c, "文案-无宿主", "no host");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "rename";
        try {
            if ("rename".equals(op)) return rename(a, h, c);
            if ("convert".equals(op)) return convert(a, h, c);
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ rename

    /** 正则批量改名：先报数量与预览，dry_run=false 才真的改名。 */
    private static String rename(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("batch.rename");
        if (deny != null) return deny;
        File root = root(h);
        if (root == null) return msg(c, "文案-无宿主", "no host");
        String p = Str.trim(a.str("dir"));
        if (p.isEmpty()) return a.missing("dir");
        String raw = Str.trim(a.str("pattern"));
        if (raw.isEmpty()) return a.missing("pattern");
        if (!a.has("replacement")) return a.missing("replacement");
        String repl = Str.nz(a.str("replacement"));
        File dir = resolve(root, p);
        if (dir == null) return outside(c, p);
        if (!dir.exists()) return msg(c, "文案-目录不存在", "no_dir {dir}", "{dir}", p);
        if (!dir.isDirectory()) return msg(c, "文案-不是目录", "not_dir {dir}", "{dir}", p);

        Pattern pat;
        try {
            pat = Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            return msg(c, "文案-正则非法", "bad regex: {error}", "{error}", Str.nz(e.getDescription()));
        }
        List<File> files = collect(root, dir, a.b("recursive", false), ext(a, "ext"), c);
        if (files.isEmpty()) return msg(c, "文案-无候选", "no_files dir={dir}", "{dir}", rel(root, dir));

        List<String[]> plan = new ArrayList<String[]>();
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String suffix = dot > 0 ? name.substring(dot) : "";
            Matcher m = pat.matcher(base);
            if (!m.find()) continue;
            String nb;
            try {
                nb = m.replaceAll(repl);
            } catch (Throwable t) {
                return msg(c, "文案-替换非法", "bad replacement: {error}", "{error}", String.valueOf(t));
            }
            if (nb.equals(base) || nb.isEmpty()) continue;
            File to;
            try {
                // 替换串里可能带路径分隔符（"../x"）：先 canonical 再比前缀，越界直接不进计划
                to = new File(f.getParentFile(), nb + suffix).getCanonicalFile();
            } catch (Throwable t) {
                skillLog(h, "skip bad target name: " + nb);
                continue;
            }
            if (!inside(root, to)) {
                skillLog(h, "skip escaping target: " + rel(root, to));
                continue;
            }
            boolean conflict = sameFile(f, to) || to.exists();
            plan.add(new String[] {f.getAbsolutePath(), to.getAbsolutePath(), conflict ? "conflict" : "ok"});
        }
        if (plan.isEmpty()) return msg(c, "文案-无匹配", "no_match dir={dir}", "{dir}", rel(root, dir));
        String tooMany = guard(c, plan.size());
        if (tooMany != null) return tooMany;

        if (dryRun(a, c)) {
            return preview(c, root, "文案-改名预览头", "文案-改名预览行", "文案-改名预览尾", plan, true);
        }
        int ok = 0;
        int skip = 0;
        int fail = 0;
        List<String> done = new ArrayList<String>();
        int limit = atLeast(P(c, "预览条数", "20"), 1, 200);
        for (String[] e : plan) {
            if (!"ok".equals(e[2])) {
                skip++;
                continue;
            }
            File from = new File(e[0]);
            File to = new File(e[1]);
            if (!inside(root, to)) {
                skillLog(h, "skip escaping target: " + rel(root, to));
                fail++;
                continue;
            }
            // 这个动作的 op 已在分支开头判过：这里只做改名（碰哪些文件由上面的候选清单定）
            boolean renamed = false;
            try {
                renamed = from.renameTo(to) && to.isFile();
            } catch (Throwable t) {
                skillLog(h, "rename failed: " + rel(root, from));
            }
            if (renamed) {
                ok++;
                if (done.size() < limit) {
                    done.add(msg(c, "文案-改名预览行", "{i}. {from} -> {to}",
                            "{i}", String.valueOf(done.size() + 1),
                            "{from}", rel(root, from), "{to}", rel(root, to)));
                }
            } else {
                fail++;
            }
        }
        String head = msg(c, "文案-改名完成", "renamed n={n} ok={ok} skip={skip} fail={fail}",
                "{n}", String.valueOf(plan.size()), "{ok}", String.valueOf(ok),
                "{skip}", String.valueOf(skip), "{fail}", String.valueOf(fail),
                "{dir}", rel(root, dir));
        return done.isEmpty() ? head : (head + "\n" + Str.join(done, "\n")).trim();
    }

    // ================================================================ convert

    /** 图片批量转格式（Java2D/ImageIO）：同样先报数量，dry_run=false 才写盘。 */
    private static String convert(Args a, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("batch.convert");
        if (deny != null) return deny;
        File root = root(h);
        if (root == null) return msg(c, "文案-无宿主", "no host");
        String p = Str.trim(a.str("dir"));
        if (p.isEmpty()) return a.missing("dir");
        String to = normalizeFormat(a.str("to"));
        if (to.isEmpty()) return a.missing("to");
        String allowed = P(c, "允许格式", ALLOW_DEF);
        if (!inList(allowed, to)) {
            return msg(c, "文案-格式不支持", "unsupported format={format} allow={allow}",
                    "{format}", to, "{allow}", allowed);
        }
        if (!ImageIO.getImageWritersByFormatName(to).hasNext()) {
            return msg(c, "文案-本机写不了", "no writer format={format}", "{format}", to);
        }
        File dir = resolve(root, p);
        if (dir == null) return outside(c, p);
        if (!dir.exists()) return msg(c, "文案-目录不存在", "no_dir {dir}", "{dir}", p);
        if (!dir.isDirectory()) return msg(c, "文案-不是目录", "not_dir {dir}", "{dir}", p);

        String outRel = Str.trim(a.str("out"));
        File outDir = dir;
        if (!outRel.isEmpty()) {
            outDir = resolve(root, outRel);
            if (outDir == null) return outside(c, outRel);
        }
        boolean overwrite = truthy(P(c, "覆盖已存在", "false"));

        List<File> files = collect(root, dir, a.b("recursive", false), ext(a, "from"), c);
        if (files.isEmpty()) return msg(c, "文案-无候选", "no_files dir={dir}", "{dir}", rel(root, dir));
        List<String[]> plan = new ArrayList<String[]>();
        for (File f : files) {
            if (extOf(f).equals(to)) continue;                       // 已经是目标格式
            File to1;
            try {
                to1 = new File(outDir, baseOf(f) + "." + to).getCanonicalFile();
            } catch (Throwable t) {
                continue;
            }
            if (!inside(root, to1)) continue;                        // 输出越界：不进计划
            boolean conflict = to1.exists() && !overwrite && !sameFile(f, to1);
            plan.add(new String[] {f.getAbsolutePath(), to1.getAbsolutePath(), conflict ? "conflict" : "ok"});
        }
        if (plan.isEmpty()) return msg(c, "文案-无匹配", "no_match dir={dir}", "{dir}", rel(root, dir));
        String tooMany = guard(c, plan.size());
        if (tooMany != null) return tooMany;

        if (dryRun(a, c)) {
            return preview(c, root, "文案-转换预览头", "文案-转换预览行", "文案-转换预览尾", plan, true);
        }
        int ok = 0;
        int skip = 0;
        int fail = 0;
        List<String> done = new ArrayList<String>();
        int limit = atLeast(P(c, "预览条数", "20"), 1, 200);
        Color bg = color(P(c, "背景色", "#FFFFFF"));
        boolean flatten = !hasAlpha(to);
        for (String[] e : plan) {
            if (!"ok".equals(e[2])) {
                skip++;
                continue;
            }
            File from = new File(e[0]);
            File to1 = new File(e[1]);
            if (!inside(root, to1)) {
                skillLog(h, "skip escaping target: " + rel(root, to1));
                fail++;
                continue;
            }
            // 这个动作的 op 已在分支开头判过：这里只读源图、写目标图
            try {
                BufferedImage img = ImageIO.read(from);
                if (img == null) {
                    skip++;
                    continue;
                }
                BufferedImage out = (flatten && img.getColorModel().hasAlpha()) ? flatten(img, bg) : img;
                File parent = to1.getParentFile();
                if (parent != null && !parent.exists()) Fs.mkdirs(parent);
                boolean wrote = ImageIO.write(out, to, to1) && to1.isFile();
                if (wrote) {
                    ok++;
                    if (done.size() < limit) {
                        done.add(msg(c, "文案-转换预览行", "{i}. {from} -> {to}",
                                "{i}", String.valueOf(done.size() + 1),
                                "{from}", rel(root, from), "{to}", rel(root, to1)));
                    }
                } else {
                    fail++;
                }
            } catch (Throwable t) {
                skillLog(h, "convert failed: " + rel(root, from));
                fail++;
            }
        }
        String head = msg(c, "文案-转换完成", "converted n={n} ok={ok} skip={skip} fail={fail}",
                "{n}", String.valueOf(plan.size()), "{ok}", String.valueOf(ok),
                "{skip}", String.valueOf(skip), "{fail}", String.valueOf(fail),
                "{dir}", rel(root, dir));
        return done.isEmpty() ? head : (head + "\n" + Str.join(done, "\n")).trim();
    }

    // ================================================================ 预览 / 闸门

    /** 试运行：只把数量与预览报出来，一个字节都不落盘。 */
    private static String preview(Cfg c, File root, String headKey, String rowKey, String tailKey,
                                  List<String[]> plan, boolean markConflict) {
        int limit = atLeast(P(c, "预览条数", "20"), 1, 200);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, headKey, "planned n={n}", "{n}", String.valueOf(plan.size()))).append('\n');
        int i = 0;
        for (String[] e : plan) {
            if (i >= limit) break;
            i++;
            String row = msg(c, rowKey, "{i}. {from} -> {to}",
                    "{i}", String.valueOf(i),
                    "{from}", rel(root, new File(e[0])),
                    "{to}", rel(root, new File(e[1])));
            if (markConflict && !"ok".equals(e[2])) row = row + msg(c, "文案-冲突尾巴", " (conflict)", "");
            sb.append(row).append('\n');
        }
        if (plan.size() > i) {
            sb.append(msg(c, tailKey, "more={more}", "{more}", String.valueOf(plan.size() - i))).append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean dryRun(Args a, Cfg c) {
        return a.b("dry_run", truthy(P(c, "默认试运行", "true")));
    }

    /** 动作前的数量闸门：候选太多就拒绝（先缩小范围，别一把梭）。 */
    private static String guard(Cfg c, int n) {
        int max = atLeast(P(c, "文件数上限", "200"), 1, 5000);
        if (n <= max) return null;
        return msg(c, "文案-文件太多", "too_many n={n} limit={limit}",
                "{n}", String.valueOf(n), "{limit}", String.valueOf(max));
    }

    // ================================================================ 文件与路径

    private static File root(Host h) {
        try {
            return h.conf().root().getCanonicalFile();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析路径；越界（含符号链接逃逸）返回 null。 */
    private static File resolve(File root, String path) {
        String p = Str.trim(path).replace('\\', '/');
        if (p.isEmpty()) p = ".";
        try {
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(root, p);
            f = f.getCanonicalFile();
            return inside(root, f) ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean inside(File root, File f) {
        String r = root.getAbsolutePath();
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

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 遍历队列项（目录 + 它还允许再往下走几层）。 */
    private static final class Item {
        final File dir;
        final int left;

        Item(File dir, int left) {
            this.dir = dir;
            this.left = left;
        }
    }

    /**
     * 收集候选文件：限深遍历，<b>跳过符号链接</b>，每个条目都再过一次 canonical 前缀校验
     * （符号链接指向目录外时也逃不出去）；按文件名排序，预览才稳定。
     */
    private static List<File> collect(File root, File base, boolean recursive, String ext, Cfg c) {
        List<File> out = new ArrayList<File>();
        int depth = recursive ? atLeast(P(c, "递归深度", "4"), 1, 12) : 0;
        int dirMax = atLeast(P(c, "目录数上限", "2000"), 10, 50000);
        List<Item> queue = new ArrayList<Item>();
        queue.add(new Item(base, depth));
        int visited = 0;
        while (!queue.isEmpty()) {
            if (++visited > dirMax) break;
            Item it = queue.remove(0);
            File[] kids = it.dir.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                try {
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue;
                } catch (Throwable ignored) {
                }
                File ck;
                try {
                    ck = k.getCanonicalFile();
                } catch (Throwable t) {
                    continue;
                }
                if (!inside(root, ck)) continue;           // 目录外的（含链接逃逸）一律跳过
                if (k.isDirectory()) {
                    if (it.left > 0 && queue.size() < dirMax) queue.add(new Item(k, it.left - 1));
                    continue;
                }
                if (!k.isFile()) continue;
                String e = extOf(k);
                if (!Str.blank(ext)) {
                    if (!inList(ext, e)) continue;
                } else if (truthy(P(c, "只收图片", "true")) && !inList(P(c, "图片后缀", "png,jpg,jpeg,gif,bmp"), e)) {
                    continue;
                }
                out.add(k);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                return x.getName().compareToIgnoreCase(y.getName());
            }
        });
        return out;
    }

    /** {@code ext} / {@code from} 参数：既支持 "png,jpg"，也支持 ".png"。 */
    private static String ext(Args a, String key) {
        return Str.trim(a.str(key)).replace(".", "").replace(" ", "");
    }

    private static String extOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1).toLowerCase() : "";
    }

    private static String baseOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static boolean inList(String list, String v) {
        if (Str.blank(list) || Str.blank(v)) return false;
        for (String p : list.split("[,，\\s]+")) {
            if (p.trim().equalsIgnoreCase(v.trim())) return true;
        }
        return false;
    }

    /** 目标格式归一化：去前导点、转小写、jpeg→jpg（ImageIO 两种都认，输出后缀统一 jpg）。 */
    private static String normalizeFormat(String s) {
        String v = Str.trim(s).toLowerCase().replace(".", "");
        if (v.isEmpty()) return "";
        if ("jpeg".equals(v)) return "jpg";
        return v;
    }

    /** 目标格式是否有 alpha 通道（没有的写前要压平）。 */
    private static boolean hasAlpha(String fmt) {
        return !("jpg".equals(fmt) || "bmp".equals(fmt));
    }

    /** 把带透明的图压到底色上（写 jpg/bmp 前用，否则透明区会变黑）。 */
    private static BufferedImage flatten(BufferedImage src, Color bg) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(bg == null ? Color.WHITE : bg);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** {@code #RRGGBB} → Color；解析不了就给白色。 */
    private static Color color(String v) {
        String s = Str.trim(v);
        if (s.startsWith("#") && s.length() >= 7) {
            try {
                return new Color(Integer.parseInt(s.substring(1, 7), 16));
            } catch (Throwable ignored) {
            }
        }
        return Color.WHITE;
    }

    // ================================================================ 配置 / 文案（prompt.md）

    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = promptText(h);
        if (Str.blank(text)) return c;
        c.kv = pairs(section(text, "配置"));
        c.tx = pairs(section(text, "文案"));
        return c;
    }

    private static String promptText(Host h) {
        try {
            File dir = h == null ? null : h.skillDir();
            if (dir == null && h != null) dir = new File(h.conf().skillsDir(), NAME);
            return dir == null ? "" : Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class Cfg {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        Map<String, String> tx = new LinkedHashMap<String, String>();
    }

    private static String P(Cfg c, String key, String def) {
        String v = c.kv.get(key);
        return Str.has(v) ? v.trim() : def;
    }

    private static int atLeast(String v, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(Str.trim(v));
        } catch (Exception e) {
            n = min;
        }
        if (n < min) n = min;
        return n > max ? max : n;
    }

    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c == null ? null : c.tx.get(key);
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

    // ================================================================ 小工具

    /**
     * 越界拒绝：明确回一句拒绝（不是"忽略"，也不是"空结果"）。
     */
    private static String outside(Cfg c, String path) {
        return msg(c, "文案-越界", "deny.outside path={path}",
                "{path}", Str.cut(Str.nz(path), 160));
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static void log(Host h, String message) {
        skillLog(h, message);
    }

    private static void skillLog(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }
}
