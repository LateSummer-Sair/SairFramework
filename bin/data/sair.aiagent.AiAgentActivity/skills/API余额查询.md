---
name: API余额查询
description: 查询 DeepSeek API 账户余额
channels: execq,execs,console
permission: ANY
category: admin
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: execq,execs,console   权限: ANY -->

调用 `balance` 工具查询当前 API 账户余额。

## 返回信息
- 账户余额和货币单位
- 已使用额度
- 赠送额度（如有）

## 频率
- 建议不超过每 10 分钟一次
