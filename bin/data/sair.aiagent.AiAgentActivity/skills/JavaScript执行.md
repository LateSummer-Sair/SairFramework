---
name: JavaScript执行
description: 通过 evaljs 工具动态执行 JavaScript 代码（Nashorn 引擎，非动态注入）
channels: console,execs
permission: ANY
category: tool
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: console,execs   权限: ANY -->

调用 `evaljs` 工具在 Nashorn 引擎中动态执行 JavaScript（脚本解释执行，非 Java 动态注入），参数 code 为代码内容。

## 可用 Java 类
- java.lang.* (基础类型)
- java.util.* (集合/工具)
- java.math.* / java.text.* (数值/日期)
- java.net.* (HTTP 请求)
- java.io.* / java.nio.file.* (文件操作)
- sair.* (SFW 框架内部 API：Libraries/SairCons 等，可反射访问运行时对象)

## 示例
- `evaljs` var x = 1 + 2; x * 10;
- `evaljs` new java.net.URL('https://api.example.com').getText()

## 确认规则
- 需确认（代码注入风险）
- execs 模式: 绕过确认

## 限制
- Nashorn 引擎，不支持 ES6+ 语法
- 本质是「动态执行」JS 脚本，不能替代 eval 的「动态注入」Java 代码作为兜底
- execq 通道: `evaljs` 不可用（动态执行已禁止）
