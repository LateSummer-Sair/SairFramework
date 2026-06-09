package sair.sys.gui.swing.tools;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import sair.sys.gui.swing.control.SFrame;

class BorderButton_Clicks {

    private final static Cursor c_def = new Cursor(Cursor.DEFAULT_CURSOR);
    private final static Cursor c_mov = new Cursor(Cursor.MOVE_CURSOR);

    final static MouseAdapter newBorderButton_Click(final SFrame frame, final BorderButton borderButton,
                                                    String border) {
        switch (border) {
            case BorderLayout.NORTH:
                return UP(frame, borderButton);
            case BorderLayout.WEST:
                return LE(frame, borderButton);
            case BorderLayout.EAST:
                return RI(frame, borderButton);
            case BorderLayout.SOUTH:
                return DN(frame, borderButton);
            default:
                return null;
        }
    }

    private static MouseAdapter DN(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fy = e.getYOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.ny = e.getYOnScreen() - borderButton.fy;
            }

            // 释放
            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getHeight() + borderButton.ny;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinHeight())
                    local = borderButton.getMinHeight();
                o_b.height = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    private static MouseAdapter RI(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fx = e.getXOnScreen();

            } // 释放

            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.nx = e.getXOnScreen() - borderButton.fx;
            }

            // 释放
            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getWidth() + borderButton.nx;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinWidth())
                    local = borderButton.getMinWidth();
                o_b.width = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    private static MouseAdapter LE(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fx = e.getXOnScreen();
            }

            // 释放
            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.nx = borderButton.fx - e.getXOnScreen();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getWidth() + borderButton.nx;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinWidth())
                    local = borderButton.getMinWidth();
                o_b.x = e.getXOnScreen();
                o_b.width = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    private static MouseAdapter UP(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fy = e.getYOnScreen();
            }

            // 释放
            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.ny = borderButton.fy - e.getYOnScreen();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getHeight() + borderButton.ny;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinHeight())
                    local = borderButton.getMinHeight();
                o_b.y = e.getYOnScreen();
                o_b.height = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

}
