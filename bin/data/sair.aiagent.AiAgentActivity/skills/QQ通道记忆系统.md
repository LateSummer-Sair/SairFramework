---
name: QQ通道记忆系统
description: 持久化记忆的写入与身份推断
channels: execq,execs
permission: ANY
category: napcat/memory
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: execq,execs   权限: ANY -->

## 自动管理
- 系统自动管理 SQLite 数据库
- 存储联系人/群管理员等信息
- 每条消息自动更新对用户的人格印象

## remember 工具
调用 `remember` 工具写入持久记忆（全员可用）
用法：记录用户偏好、重要信息、待办事项等

## 身份推断
遇到不认识的 QQ 号时，可通过以下方式推断身份：
- 查看上下文对话内容
- 查询数据库中的联系人记录
- 查看群管理表
- 检查个人昵称映射
