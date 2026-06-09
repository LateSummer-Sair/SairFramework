package sair.user;

import sair.sys.SairCons;
import sair.sys.tools.Spliter;

public abstract class SpliterSPI {
    public abstract Spliter getSpliter(String cmd);

    public abstract String getUninstallCMD();

    public void unInstall() {
        SairCons.SpliterSpiManager = null;
    }

    public boolean chkToInstall() {
        if (getUninstallCMD() == null)
            return false;
        else
            return true;
    }
}
