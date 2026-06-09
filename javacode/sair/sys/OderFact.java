package sair.sys;

import sair.FCM;
import sair.sys.acticity.Exection;
import sair.sys.tools.Spliter;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

class OderFact {

    private final static OderFact OF = new OderFact();

    public static Object runner(Activity localActivity, Spliter sp) {
        String funcName = sp.getExecFunc();
        if (localActivity.containsOrderName(funcName))
            funcName = localActivity.getOldOrderName(funcName);
        String localArgs = sp.getArgs();
        switch (funcName) {
            case "help":
                return OF.help(localActivity);
            case "exit":
                return OF.exit(localActivity);
            case "info":
                return OF.info(localActivity);
            case "ofunc":
                return OF.ofunc(localActivity, sp);
            case "close":
                return OF.close(localActivity);
            case "open":
                return OF.open(localActivity);
            case "oset":
                return OF.oset(localActivity, localArgs);
            case "orem":
                return OF.orem(localActivity, localArgs);
            default:
                return OF.defaultFunc(localActivity, funcName, localArgs);
        }
    }

    private boolean help(Activity localActivity) {
        if (!localActivity.isOpen())
            return false;
        SairCons.printHelp(localActivity);
        return true;
    }

    private boolean exit(Activity localActivity) {
        if (!localActivity.isOpen())
            return false;
        localActivity.exit();
        return true;
    }

    private boolean info(Activity localActivity) {
        if (!localActivity.isOpen())
            return false;
        String name = localActivity.getName();
        if (name == null)
            name = "framework";
        Exection exec = Libraries.exections.get(localActivity);
        SairCons.println(FCM.EXECTION_pathInfo_Color, name + " --> " + localActivity.getDataDir());
        if (exec == null)
            return true;
        SairCons.println(FCM.EXECTION_pathInfo_Color, name + " --> " + exec.getPath());
        return true;
    }

    private Object ofunc(Activity localActivity, Spliter sp) {
        if (!localActivity.isOpen())
            return false;
        return ToolPack.toSystemSpliter_ofunc(sp, localActivity);
    }

    private boolean close(Activity localActivity) {
        localActivity.close();
        return true;
    }

    private Object defaultFunc(Activity localActivity, String funcName, String localArgs) {
        if (!localActivity.isOpen())
            return false;
        return SairCons.toActiRun(localActivity, funcName, localArgs);
    }

    private boolean orem(Activity localActivity, String localArgs) {
        localActivity.removeOrderName(localArgs);
        return true;
    }

    private boolean open(Activity localActivity) {
        localActivity.open();
        return true;
    }

    private boolean oset(Activity localActivity, String localArgs) {
        String[] argsSplit = localArgs.split(" ");
        if (argsSplit.length < 2) {
            SairCons.println("args ERROR!");
            return true;
        }
        String oldName = argsSplit[1], newName = argsSplit[0];
        if (oldName.trim().equals("") || newName.trim().equals("")) {
            SairCons.println("args has null!");
            return true;
        }
        localActivity.putOrderName(newName, oldName);
        return true;
    }

}
