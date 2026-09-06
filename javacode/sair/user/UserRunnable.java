package sair.user;

/**
 * 组件回调契约(包私有):Activity 继承它向框架提供 main/help/exit 三个挂钩。
 * <p>
 * 架构角色:插件生命周期的调用面——命令分发(OderFact/SairCons.toActiRun)
 * 经 main 调用插件函数,help 提供帮助文本,exit 是插件的<b>退出前置钩子</b>。
 * <p>
 * 二进制兼容约束:本接口包私有,不构成对外 API;但 Activity 已实现此三方法,
 * 其行为约定(main 的返回值语义、help 的逐行文本、exit 的钩子语义)不可改变。
 */
interface UserRunnable {
	/**
	 * 调用插件函数。
	 *
	 * @param funcName 函数名(别名已还原为原始名)
	 * @param args     参数串
	 * @return 执行结果;null 表示已处理,Boolean.FALSE 表示失败(框架会打印帮助)
	 */
	Object main(String funcName, String args);

	/**
	 * 提供帮助文本(逐行)。
	 *
	 * @return 帮助行数组
	 */
	String[] help();

	/**
	 * <b>退出前置钩子</b>(设计约束:插件<b>必须实现</b>,这是编译期强制契约):
	 * 由 <code>插件名/exit</code> 命令直接转发调用——框架本身不做任何资源释放/调度,
	 * 不约束插件自身行为(无返回值校验、不捕获异常),是否退出由插件自己决定;
	 * 框架执行 uninstall 卸载时也会自动调用一次本方法做卸载前收尾。
	 */
	void exit();
}
