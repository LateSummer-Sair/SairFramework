import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import sair.sacoms.MathCast;
import sair.sacoms.Randoms;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;

public class Tools {

    private SFrame sf;

    public int sum(String as, String bs) {
        int a = MathCast.StringsIntToInt(String.valueOf(as));
        int b = MathCast.StringsIntToInt(String.valueOf(bs));
        return a + b;
    }

    public int max(String as, String bs) {
        int a = MathCast.StringsIntToInt(String.valueOf(as));
        int b = MathCast.StringsIntToInt(String.valueOf(bs));
        if (a < b)
            return b;
        else
            return a;
    }

    public int min(String as, String bs) {
        int a = MathCast.StringsIntToInt(String.valueOf(as));
        int b = MathCast.StringsIntToInt(String.valueOf(bs));
        if (a > b)
            return b;
        else
            return a;
    }

    public int del(String as, String bs) {
        int a = MathCast.StringsIntToInt(String.valueOf(as));
        int b = MathCast.StringsIntToInt(String.valueOf(bs));
        if (a > b)
            return b - a;
        else
            return a - b;
    }

    public void rp() {
        @SuppressWarnings("unchecked")
        ArrayList<String> list = (ArrayList<String>) SairCons.runner(false, "pl/getListToOvar");
        final int min = 0;
        final int max = list.size();

        int id = Randoms.nextInt(min, max);
        SairCons.runner(false, "pl/start " + id);
    }

    public void testframe() {
        if (sf == null) {
            sf = new SFrame() {
                private static final long serialVersionUID = 7013586041367834979L;

                public SFrame init(int w, int h) {
                    super.set(w, h);
                    this.centerPanel.setLayout(new BorderLayout());
                    SButton bt = new SButton("close");
                    bt.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            setVisible(false);
                        }
                    });
                    this.centerPanel.add(bt, BorderLayout.CENTER);
                    return this;
                }
            }.init(800, 600);
        }
        sf.setVisible(true);
    }

}