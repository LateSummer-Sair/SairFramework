package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.List;

import javax.swing.text.JTextComponent;

/**
 * <p>
 * 文件拖放适配器（包私有）：接收拖入的文件列表，把第一个文件的绝对路径
 * 以引号包裹追加到文本组件（{@link JTextComponent}）的现有内容之后。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层内部实现，由 {@code DragAc#toDrag(Component)} 作为
 * {@link DropTarget} 的处理器挂接；仅支持 {@link DataFlavor#javaFileListFlavor}，
 * 其他拖放类型一律 rejectDrop。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>{@link #drop} 由 AWT 的 DnD 事件线程回调（非 EDT）；
 * 对 Swing 文本组件的 {@code setText} 直接调用是历史实现约定，改动需评估。
 * 实例只绑定单一组件（{@link #components}），勿跨线程共享。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与成员均为包私有，但 {@code DragAc} 依赖其构造与
 * {@link #setComponents(Component)} 链式调用，签名不可随意修改。
 * </p>
 */
class DragAcAdapter extends DropTargetAdapter {

    /** 本次拖放事件的目标组件（接收文件路径的组件）。 */
    private Component components;

    /**
     * 绑定本次拖放事件的目标组件（链式返回 this，供
     * {@code new DragAcAdapter().setComponents(c)} 直接作为 DropTarget 监听器）。
     *
     * @param component 接收文件路径的组件
     * @return this（链式调用）
     **/
    DropTargetAdapter setComponents(Component component) {
        components = component;
        return this;
    }

    /**
     * 拖放完成回调（DnD 事件线程）：支持文件列表则 acceptDrop 并处理；
     * 空列表（null 或空）走失败路径 {@code dropComplete(false)}（修复版：
     * 不再抛 IOOBE，且补全拖放手势避免界面卡在拖动状态）；
     * 不支持的类型 rejectDrop；任何异常同样 dropComplete(false) 收尾。
     *
     * @param dtde 拖放放下事件
     **/
    @Override
    public void drop(DropTargetDropEvent dtde) {
        try {
            if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                @SuppressWarnings("unchecked")
                List<File> list = (List<File>) (dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                // 修复:空文件列表不再IOOBE,失败路径补dropComplete避免拖放手势卡住
                if (list == null || list.isEmpty()) {
                    dtde.dropComplete(false);
                    return;
                }
                dragResponsePlus(list, components);
                dtde.dropComplete(true);
            } else
                dtde.rejectDrop();
        } catch (Exception e) {
            dtde.dropComplete(false);
        }
    }

    /**
     * 私有：取文件列表中<b>第一个</b>文件的绝对路径，以引号包裹追加到组件文本
     * （仅对 {@link JTextComponent} 生效，其余组件类型静默忽略）。
     **/
    private void dragResponsePlus(List<File> list, Component component) {
        String filePath = list.get(0).getAbsolutePath();
        if (component instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) component;
            text.setText(text.getText() + "\"" + filePath + "\"");
        }
    }
}