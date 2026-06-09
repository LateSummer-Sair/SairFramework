package sair.sys;

import java.io.File;
import java.util.HashMap;

import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.user.Activity;

public final class Libraries {
    public final static HashMap<Activity, Exection> exections = new HashMap<Activity, Exection>();
    public final static HashMap<String, Mod> mods = new HashMap<String, Mod>();
    public final static HashMap<String, Activity> activities = new HashMap<String, Activity>();

    private final static HashMap<String, Integer> actiNameRulMana = new HashMap<String, Integer>();

    public static String setActivityName(String actiPath) {
        final String name = new File(actiPath).getName().split("\\.")[0];

        if (actiNameRulMana.containsKey(name)) {

            Integer actiRul_ID = actiNameRulMana.get(name);

            actiRul_ID++;

            actiNameRulMana.put(name, actiRul_ID);

            return name + actiRul_ID;

        } else {

            actiNameRulMana.put(name, -1);

            return name;

        }
    }
}
