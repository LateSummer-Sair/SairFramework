---
name: Java动态注入
description: 通过 eval 工具一次性编译执行 Java 代码（动态注入，万能兜底）
channels: console,execs
permission: ANY
category: tool
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: console,execs   权限: ANY -->

调用 `eval` 工具编译并执行一段 Java 代码，参数 code 为源码内容。

## 核心约定（必须遵守）
- 源码可以无 package 声明（有 package 也能正确处理）
- 类中必须定义 `public Object run()` 方法，而不是 main 方法
- 执行结果由 `run()` 的返回值给出（返回 null 则显示 null）
- 全程一次性：编译→加载→实例化→调用 run()→丢弃，不保留任何状态

## 万能兜底
- eval 是任何技能的最终兜底选项：常规工具连续失败 5 次后，系统会自动引导你用 eval 编写代码直接完成任务
- 免手动编译，可调用 SFW 框架内部任何 API，也能反射得到当前运行时全部对象

## 可访问的 SFW 内部 API
- `sair.sys.Libraries.activities`: 组件注册表 Map<String, Activity>，遍历可获取所有已加载组件及名称
- `sair.sys.Libraries.mods` / `sair.sys.Libraries.exections`: 模块与执行器注册表
- `sair.sys.SairCons.runner(boolean isMark, String cmd)`: 执行 SFW 命令，cmd 格式 `组件名/功能名 参数`，返回 Object 结果
- `sair.sys.SairCons.toActiRun(Activity, funcName, args)`: 直接调用组件的 main 方法
- `sair.user.Activity.main(String funcName, String args)`: 组件功能入口（返回 Object）
- `sair.user.Activity.help()`: 返回组件帮助信息 String[]
- `sair.user.Activity.getName()` / `getDataDir()`: 组件名与数据目录
- `sair.LoaderManager.loader`: SairLoader 加载器

## 完整示例
- `eval` class Hello { public Object run() { return "hello " + (1+1); } }
- `eval` class Sum { public Object run() { int s=0; for(int i=1;i<=100;i++) s+=i; return s; } }
- `eval` class ListActs { public Object run() { return sair.sys.Libraries.activities.keySet().toString(); } }
- `eval` class RunCmd { public Object run() { return sair.sys.SairCons.runner(false, "组件名/功能名 参数"); } }

## 编译环境
- 使用 JDK 的 JavaCompiler 内存编译，无需落盘
- 编译 classpath 自动包含 ai.jar、SFW.jar 与 SFW 依赖 jars
- 源码按 UTF-8 编码编译

## 确认规则
- 需确认（代码注入风险）
- execs 模式: 绕过确认

## 限制
- 最长执行时间 30 秒
- 输出结果截断 10000 字符
- 缺少无参 run() 方法会返回编译/执行错误提示
- execq 通道: `eval` 仅限「多对象拆分搜索」破例可用，其他场景仍禁用
