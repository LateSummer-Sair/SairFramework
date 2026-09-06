package sair.sys;

import java.util.List;

/**
 * IR 标签块数据对象:保存标签名与块内命令行集合,由 IRRunnable 的标签预解析
 * (toMakeLabel)构造,/TO: 标签跳转时读取。
 * <p>
 * 架构角色:IR 解释环节的纯数据载体(无行为);name/lines 在解析阶段写入,
 * 运行阶段只读,无需额外同步。
 * <p>
 * 二进制兼容约束:getter/setter 方法对(public)的签名不可变更。
 */
public class IRLabel {
	/**
	 * 标签名("/TO:" 跳转目标;行尾 "{" 前的文本)。
	 */
	private String name;

	/**
	 * 标签块内的命令行集合(已剔除空行)。
	 */
	private List<String> lines;

	/**
	 * 读取标签名。
	 */
	public String getName() {
		return name;
	}

	/**
	 * 设置标签名(标签预解析时调用)。
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * 读取标签块内命令行集合。
	 */
	public List<String> getLines() {
		return lines;
	}

	/**
	 * 设置标签块内命令行集合(标签预解析时调用)。
	 */
	public void setLines(List<String> lines) {
		this.lines = lines;
	}
}
