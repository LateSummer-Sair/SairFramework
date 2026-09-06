package sair.sys.tools;

/**
 * 命令解析结果接口:一次解析产生 组件名/函数名/参数 三段信息。
 * <p>
 * 架构角色:命令解释环节的产物契约——SairCons 只面向本接口工作,默认实现为
 * SystemSpliter,插件可经 {@link sair.user.SpliterSPI} 提供自定义解析器
 * (ToolPack.setSpliter 安装)。
 * <p>
 * 二进制兼容约束:三个方法的签名不可变更。
 */
public interface Spliter {
    /**
     * 组件名段:空串表示控制台默认 FrameActivity;null 表示不执行(跳过)。
     */
    String getExecName();

    /**
     * 函数名段:交给 OderFact 做内置命令分发或组件方法调用。
     */
    String getExecFunc();

    /**
     * 参数段:原样透传给目标函数。
     */
    String getArgs();
}
