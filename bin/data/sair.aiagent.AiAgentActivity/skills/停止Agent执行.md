---
name: 停止Agent执行
description: 通过 stop 工具立即停止当前 Agent 循环
channels: console,execs
permission: ANY
category: control
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: console,execs   权限: ANY -->

调用 `stop` 工具立即停止 Agent 的当前执行循环。

## 效果
- Agent 立即退出当前任务循环
- 已完成的操作不会被回滚

## 确认规则
- 无需确认，直接执行

## 使用场景
- 任务目标已达成，无需继续
- 用户要求停止
- 检测到死循环或无意义重复
