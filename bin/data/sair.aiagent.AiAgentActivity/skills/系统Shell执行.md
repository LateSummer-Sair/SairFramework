---
name: 系统Shell执行
description: 通过 sys 工具执行系统 Shell 命令
channels: console,execs
permission: ANY
category: tool
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: console,execs   权限: ANY -->

调用 `sys` 工具执行系统 Shell 命令，参数 command 为命令内容。

## 特性
- 实时捕获 stdout 和 stderr 输出
- 最长等待 35 秒后超时
- 根据操作系统自动选择 Shell

## 示例
- `sys` dir C:\Users (Windows)
- `sys` ls -la /home (Linux)
- `sys` pip install requests

## 确认规则
- 需确认: 高危操作会弹出确认对话框
- execs 模式: 绕过确认

## 安全
- execq 通道: `sys` 工具不可用
