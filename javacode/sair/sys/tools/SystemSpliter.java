package sair.sys.tools;

import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.Safe;
import sair.sys.SairCons;

/**
 * 默认命令解析器:把命令字符串拆分为 组件名/函数名/参数 三段,支持 %var%
 * 变量展开与 '...' 内嵌命令执行。
 * <p>
 * 架构角色:命令解释环节的默认实现——SairCons 在未安装 SPI 时
 * (ToolPack.findSplited)使用本类解析;解析产物经 Spliter 接口交给 OderFact 分发。
 * <p>
 * 线程安全说明:{@link #vmap} 为 Safe.map 同步表,跨线程可写(经 ToolPack.getVmap
 * 公开);实例字段(name/func/args/o)构造后不再变更,实例不跨线程共享;
 * 递归展开(reChk)深度上限 64,防止 %var% 循环引用无限递归。
 * <p>
 * 二进制兼容约束:本类包私有,不构成对外 API;但 getExecName/getExecFunc/
 * getArgs/getO 的实现语义被框架(ToolPack/OderFact)依赖,不可改变;
 * 命令语法(组件/函数 参数、%var%、'...')为脚本文件格式契约。
 */
class SystemSpliter implements Spliter {

    /**
     * 全局变量表(线程安全):%var% 展开源;%irp% 初始为空串(IR 路径占位),
     * 经 ToolPack.getVmap 公开,插件可动态增删。
     */
    static final HashMap<String, String> vmap = Safe.map();

    static {
        vmap.put("%irp%", "");
    }

    /**
     * %var% 变量匹配模式(非贪婪)。
     */
    private static final String patternCmd = "%(.*?)%";

    /**
     * '...' 内嵌命令匹配模式。
     */
    private static final String pattern2R = "'(.*?)'";

    /**
     * 解析结果:name=组件名段,func=函数名段。
     */
    private String name, func;

    /**
     * 解析结果:参数段(命令其余部分)。
     */
    private String args;

    /**
     * 内嵌执行结果对象('...' 命令的返回值,无内嵌时为 null;供 ofunc 使用)。
     */
    private Object o;

    /**
     * 解析命令:以 "//" 开头视为注释,不解析;否则:
     * <ol>
     * <li>先做单趟 %var% 展开(chk_replace);若展开改变了内容且函数为 var-add,
     *     改用原文重新解析(变量定义命令本身不应再被展开);</li>
     * <li>否则 reChk 迭代展开至稳定;</li>
     * <li>非 var-add 时检查 '...' 内嵌命令:执行并把返回值替换回参数,对象存入 o。</li>
     * </ol>
     *
     * @param cmd 完整命令字符串
     */
    public SystemSpliter(String cmd) {
        /// /var-add ppid /println-c 255 0 0 'pl/getNowPlayID'

        if (cmd.length() >= 2 && cmd.charAt(0) == '/' && cmd.charAt(1) == '/')
            return;
        String chked = chk_replace(cmd);
        init(chked);
        if (!cmd.equals(chked) && "var-add".equals(getExecFunc()))
            init(cmd);
        else
            init((chked = reChk(chked)));
        if (!"var-add".equals(getExecFunc())) {
            Object[] chkro = chk_R(chked);
            if (chkro != null) {
                if (chkro.length == 2) {
                    this.o = chkro[1];
                }
                if (chkro[0] instanceof String) {
                    String local = (String) chkro[0];
                    init(local);
                }

            }

            /*
             * if (chkro instanceof String) { chked = (String) chkro;
             * init(chked); } else { this.o = chkro; return; }
             */
        }
    }

    /**
     * 检查并执行 '...' 内嵌命令:无单引号时快速返回原文(性能优化,跳过正则);
     * 取出首个引号段,经 toRunner 执行(SairCons.runner),返回值替换回命令。
     *
     * @param cmd 命令字符串
     * @return 数组:{替换后的命令, 内嵌执行结果对象}(无内嵌段时仅含命令一个元素)
     */
    static Object[] chk_R(String cmd) {
        // 性能优化:无单引号时跳过正则匹配
        if (cmd.indexOf('\'') < 0)
            return new Object[] { cmd };

        Pattern p = Pattern.compile(pattern2R);
        Matcher m = p.matcher(cmd);
        String r = cmd;
        try {
            m.find();
            r = m.group();
        } catch (Exception e) {
            return new Object[]{cmd};
        }
        Object[] result = new Object[2];

        if (r != null && !"".equals(r)) {
            result[1] = toRunner(r);
            if (result[1] != null)
                result[0] = cmd.replace(r, String.valueOf(result[1]));
        } else
            return new Object[]{cmd};

        return result;
    }

    /**
     * 执行引号段内容:去掉首尾单引号后作为命令投递 SairCons.runner(不记历史),
     * 返回执行结果(命令过短时返回空串)。
     */
    private static Object toRunner(String cmd) {
        if (cmd.length() < 2)
            return "";
        StringBuffer sbf = new StringBuffer(cmd);
        sbf.deleteCharAt(sbf.length() - 1).deleteCharAt(0);
        cmd = sbf.toString();
        Object result = SairCons.runner(false, cmd);
        return result;
    }

    /**
     * 单趟 %var% 展开:无 % 时快速返回(性能优化,跳过正则);
     * 收集本命令中出现的全部变量,统一用 vmap 当前值替换
     * (同名变量只替换一次,避免重复替换歧义;vmap 中不存在的变量保持原样)。
     */
    static String chk_replace(String cmd) {
        // 性能优化:无%时跳过正则匹配
        if (cmd.indexOf('%') < 0)
            return cmd;
        Pattern p = Pattern.compile(patternCmd);
        Matcher m = p.matcher(cmd);

        HashMap<String, String> localMap = new HashMap<String, String>();

        while (m.find()) {
            String oe = m.group();
            String ne = vmap.get(oe);
            if (null == ne || localMap.containsKey(oe))
                continue;
            else {
                localMap.put(oe, ne);
            }
        }

        Iterator<String> it = localMap.keySet().iterator();

        while (it.hasNext()) {
            String next = it.next();
            cmd = cmd.replace(next, localMap.get(next));
        }

        return cmd;
    }

    /**
     * 迭代展开入口:反复 chk_replace 直到内容稳定或超过步数上限。
     */
    private String reChk(String cmd) {
        return reChk(cmd, 0);
    }

    /**
     * 迭代展开实现:%var% 循环映射(A→B,B→A)时展开永不收敛,
     * 步数上限 64 防止无限递归(超限打印告警并按当前结果返回);
     * 每趟展开后重解析(init)以刷新函数名判断。
     */
    private String reChk(String cmd, int depth) {
        // 修复:%var%循环映射(A->B,B->A)展开步数上限,防止无限递归
        if (depth > 64) {
            SairCons.println(sair.FCM.Error_Color, "疑似存在%var%循环引用,已停止展开");
            return cmd;
        }
        if (cmd.contains("%")) {
            String chked = chk_replace(cmd);
            if ((!cmd.equals(chked))) {
                cmd = chk_replace(cmd);
                init(cmd);
                cmd = reChk(cmd, depth + 1);
            }
        }
        return cmd;
    }

    /**
     * 三段式拆分:遍历字符,首个 '/' 前为组件名段,其后到第一个空格前为函数名段,
     * 其余为参数段;缺失的分隔符对应段为空串。
     * (示例:"acti/func arg1 arg2" → name=acti, func=func, args="arg1 arg2")
     */
    private void init(String cmd) {
        StringBuffer nameBuf = new StringBuffer();
        StringBuffer funcBuf = new StringBuffer();
        StringBuffer argsBuf = new StringBuffer();

        char[] cs = cmd.toCharArray();
        boolean hasHead = false, hasFunc = false;
        StringBuffer local = nameBuf;
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];

            if (!hasFunc && c == '/') {
                local = funcBuf;
                hasHead = true;
                continue;
            } else if (!hasFunc && hasHead && c == ' ') {
                local = argsBuf;
                hasFunc = true;
                continue;
            }
            local.append(c);
        }

        this.args = argsBuf.toString();
        this.name = nameBuf.toString();
        this.func = funcBuf.toString();
    }

    /**
     * 组件名段(命令第一段;空串表示指向控制台默认 FrameActivity)。
     */
    @Override
    public String getExecName() {
        return name;
    }

    /**
     * 函数名段(命令第二段)。
     */
    @Override
    public String getExecFunc() {
        return func;
    }

    /**
     * 参数段(命令其余部分,可能为空串)。
     */
    @Override
    public String getArgs() {
        return args;
    }

    /**
     * 调试用字符串:输出 name/func/args 三段内容。
     */
    public String toString() {
        return new StringBuffer().append("[name:").append(name).append("] [func:").append(func).append("] [args:")
                .append(args).append(']').toString();
    }

    /**
     * 内嵌执行结果对象('...' 命令返回值,无内嵌时为 null);
     * 供 ofunc 透传给 Activity.o_funcMain。
     */
    public Object getO() {
        return o;
    }
}