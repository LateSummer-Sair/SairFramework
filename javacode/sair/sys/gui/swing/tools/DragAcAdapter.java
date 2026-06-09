package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.List;

import javax.swing.text.JTextComponent;

class DragAcAdapter extends DropTargetAdapter {

    private Component components;

    DropTargetAdapter setComponents(Component component) {
        components = component;
        return this;
    }

    @Override
    public void drop(DropTargetDropEvent dtde) {
        try {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                @SuppressWarnings("unchecked")
                List<File> list = (List<File>) (dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                dragResponsePlus(list, components);
                dtde.dropComplete(true);
            } else
                dtde.rejectDrop();
        } catch (Exception e) {
        }
    }

    private void dragResponsePlus(List<File> list, Component component) {
        String filePath = list.get(0).getAbsolutePath();
        if (component instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) component;
            text.setText(text.getText() + "\"" + filePath + "\"");
        }
    }
}