---
name: QQ文件读取
description: 读取通过 QQ 发送的文件内容（文件下载/获取 API）
channels: execq,execs
permission: ANY
category: napcat/file
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: execq,execs   权限: ANY -->

调用 `readfile` 工具读取文本文件内容。

## 底层文件获取 API
- get_file(file_id 或 file): 下载文件到本地或输出 base64
- get_image(file): 获取图片文件数据
- get_record(file_id/file, out_format): 获取语音（支持 mp3/amr/wma/m4a/spx/ogg/wav/flac 转码）
- get_group_file_url(file_id, group): 获取群文件直链
- get_private_file_url(file_id): 获取私聊文件直链

## 示例
- `readfile` config.txt

## 注意
- 接收文件时大部分提供 URL 上报，无 URL 时需用 get_file 获取本地文件
- 普通文件链接有下载次数限制，可再次调用 get_*_file_url 刷新直链
- 大文件可能被截断
