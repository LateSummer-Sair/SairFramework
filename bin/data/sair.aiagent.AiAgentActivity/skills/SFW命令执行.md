---
name: SFW命令执行
description: 通过 cmd 工具执行 SFW 插件命令或查询帮助
channels: execq,execs,console
permission: ANY
category: tool
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: execq,execs,console   权限: ANY -->

调用 `cmd` 工具执行 SFW 框架命令，参数 command 格式为 pluginName/funcName args。

## 格式
- 插件名/函数名 参数: 执行插件函数并返回控制台输出

## 示例
- `cmd` FileManager/delete temp.txt
- `cmd` NetworkUtils/ping 8.8.8.8

## 查询帮助（execq 通道）
- `cmd` /help: 查看 SFW 框架自身帮助 + 可用组件列表
- `cmd` 组件名/help: 查看指定组件的帮助（如 `cmd` ai/help 查看 ai 组件帮助）

## 确认规则
- 需确认: ai/yes 通过 | ai/no 拒绝 | 60s 超时自动拒
- execs 模式绕过所有确认

## 限制
- execq 通道: cmd 仅允许 help 查询（/help 或 组件名/help），其他命令一律禁止
- 本地通道: 无限制
