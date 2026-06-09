package sair.sys.tools;

import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.sys.SairCons;

class SystemSpliter implements Spliter {

    static final HashMap<String, String> vmap = new HashMap<String, String>() {
        private static final long serialVersionUID = -243929172743474485L;

        HashMap<String, String> init() {
            put("%irp%", "");
            return this;
        }
    }.init();

    private static final String patternCmd = "%(.*?)%";
    private static final String pattern2R = "'(.*?)'";

    private String name, func;
    private String args;
    private Object o;

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

    static Object[] chk_R(String cmd) {

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

    private static Object toRunner(String cmd) {
        if (cmd.length() < 2)
            return "";
        StringBuffer sbf = new StringBuffer(cmd);
        sbf.deleteCharAt(sbf.length() - 1).deleteCharAt(0);
        cmd = sbf.toString();
        Object result = SairCons.runner(false, cmd);
        return result;
    }

    static String chk_replace(String cmd) {
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

    private String reChk(String cmd) {
        if (cmd.contains("%")) {
            String chked = chk_replace(cmd);
            if ((!cmd.equals(chked))) {
                cmd = chk_replace(cmd);
                init(cmd);
                cmd = reChk(cmd);
            }
        }
        return cmd;
    }

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

    @Override
    public String getExecName() {
        return name;
    }

    @Override
    public String getExecFunc() {
        return func;
    }

    @Override
    public String getArgs() {
        return args;
    }

    public String toString() {
        return new StringBuffer().append("[name:").append(name).append("] [func:").append(func).append("] [args:")
                .append(args).append(']').toString();
    }

    public Object getO() {
        return o;
    }
}