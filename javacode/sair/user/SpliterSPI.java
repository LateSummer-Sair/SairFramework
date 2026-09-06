package sair.user;

import sair.sys.SairCons;
import sair.sys.tools.Spliter;

/**
 * 自定义命令解析器 SPI:插件实现后经 ToolPack.setSpliter 安装,
 * 替换默认 {@link sair.sys.tools.SystemSpliter} 接管命令解析。
 * <p>
 * 架构角色:命令解释环节的可替换前端——SairCons 解析命令时经 ToolPack.findSplited
 * 委托给本 SPI 的 getSpliter;卸载命令字符串由 getUninstallCMD 声明,
 * ToolPack.SpliterChkUninstall 负责匹配与摘除。
 * <p>
 * 线程安全说明:实现对象写入 SairCons.SpliterSpiManager(volatile),命令线程读取;
 * getSpliter 会被频繁调用,实现需无状态或线程安全。
 * <p>
 * 二进制兼容约束:抽象方法 getSpliter/getUninstallCMD 与公开方法
 * unInstall/chkToInstall 的签名不可变更。
 */
public abstract class SpliterSPI {
	/**
	 * 解析命令为 Spliter 结果(替代默认解析器)。
	 *
	 * @param cmd 完整命令字符串
	 * @return 解析结果(组件名/函数名/参数三段信息)
	 */
	public abstract Spliter getSpliter(String cmd);

	/**
	 * 声明本 SPI 的卸载命令字符串:命令与之完全相等时触发卸载。
	 * 返回 null 表示不可卸载(此时 chkToInstall 亦拒绝安装)。
	 *
	 * @return 卸载命令(可为 null)
	 */
	public abstract String getUninstallCMD();

	/**
	 * 默认卸载实现:直接清空全局 SPI 管理器,恢复默认解析器。
	 * 覆写时应保证最终摘除 SairCons.SpliterSpiManager。
	 */
	public void unInstall() {
		SairCons.SpliterSpiManager = null;
	}

	/**
	 * 安装前置校验:卸载命令非 null 才允许安装。
	 *
	 * @return true 表示可安装
	 */
	public boolean chkToInstall() {
		if (getUninstallCMD() == null)
			return false;
		else
			return true;
	}
}
