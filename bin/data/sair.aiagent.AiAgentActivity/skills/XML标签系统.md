---
name: XML标签系统
description: 使用XML标签调用系统功能
channels: console,execs
permission: ANY
category: core
---

<!-- 由内置技能库导出（V3.14 迁移）：原 SystemSkillLib/NapCatSkillLib 中的硬编码技能，已转为单文件技能。
     通道: console,execs   权限: ANY -->

所有可用的操作标签及其用法。

## 本地通道可用标签
- <cmd>command</cmd>: 执行命令/调用插件（格式: pluginName/funcName args）
- <sys>command</sys>: 执行系统命令
- <evaljs>code</evaljs>: 执行JavaScript代码（Nashorn引擎）
- <eval>code</eval>: 动态注入标签（eval中输出XML标签会被解析执行）
- <web>url</web>: 获取网页内容
- <readfile>path</readfile>: 读取文件
- <readdir>path</readdir>: 列出目录
- <download>url</download>: 下载文件
- <remember>content</remember>: 记录持久化记忆
- <sendimage>path</sendimage>: 发送本地图片
- <sendrecord>path</sendrecord>: 发送语音消息
- <sendfile>path</sendfile>: 发送文件
- <editprompt>content</editprompt>: 修改系统提示词
- <superise>content</superise>: 弹出彩蛋窗口
- <schedule>cron command</schedule>: 创建定时任务
- <note>...</note>: 知识库操作（add/search/list/get/delete/update）
- <searchnote>query</searchnote>: 搜索知识库
- <batchrename>dir=/path pattern=regex replacement=text</batchrename>: 批量重命名
- <batchconvert>dir=/path from=EXT to=EXT</batchconvert>: 批量图片格式转换
- <balance/>: 查询API余额
- <weather>city</weather>: 查询天气
- <skillextract>description</skillextract>: 从对话轨迹中蒸馏技能
- <stop/>: 停止当前任务

## execq通道额外标签
- <setname>name</setname>: 设置机器人名字
- <sendsticker>context</sendsticker>: 发送表情包
- <collectsticker>url|context</collectsticker>: 收藏表情包
- <poke/>: 戳一戳（由QqActionProcessor处理）

## 标签权限
- 本地通道: execq标签不可用
- QQ execq通道: 仅白名单标签可用
