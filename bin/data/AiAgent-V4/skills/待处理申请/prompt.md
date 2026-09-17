# 待处理申请 · 配置与文案
# 本文件是「待处理申请」技能的配置源：保存即生效（基板监听技能目录，自动热重载）。
# 格式：一行一条「键: 值」；# 开头的行是注释；值为空 = 该键用技能里的结构性 ASCII 兜底。

# ---- 配置 ----
启用: true
仅主人: true
待处理最多: 50
默认加群子类型: add
# 系统消息动作：留空 = pending 只读 kv 清单（不查实时）。
# 填 NapCat 支持的动作名（要能从 napcat 工具 action=list 的目录里挑到，基板目录见 Api.catalog()）后，pending 会实时问一次群系统消息。
# 这个动作属 E 类对外动作：非主人默认拒（要放开得主人在账本 perms.json 写 E 例外）。
系统消息动作: 

# ---- 文案 ----
未启用: 待处理申请技能在 prompt.md 里被关掉了（启用: false）。
执行异常: 待处理申请执行出错：{err}
缺参数: 缺少参数 {k}，请补上再调用（type=friend/group，flag 从 pending 清单里抄）。
不支持的操作: 不支持的 op「{op}」。可用：{ops}
类型无法识别: 认不出申请类型「{type}」—— 只能用 friend（加好友）或 group（加群/邀请）。
权限不足: {deny}
操作失败: {op} 失败：{err}
没有待处理: 现在没有待处理的好友申请或群邀请。
申请头: 待处理申请 {n} 条：
申请行: {i}. {type}｜QQ {qq}｜群 {group}｜flag={flag}｜{subtype}｜验证信息「{comment}」｜来源 {source}
钩子记录说明: 其中 {n} 条来自「申请审批」钩子的最近记录（键：{keys}），它们不是实时快照，可能已经被处理过。
实时查询失败: 实时查询没成功：{err}
同意已执行: 已同意这条 {type} 申请（flag={flag}）。
拒绝已执行: 已拒绝这条 {type} 申请（flag={flag}）。
清单已移出: 已从待处理清单里移出 {key}。

# ---- 说明 ----
# 仅主人：这个键已不参与判定（approve/reject 属 E 类对外动作，非主人一律拒）。
# 待处理最多：pending 最多列多少条。
# 默认加群子类型：approve/reject 处理群申请时，没给 subtype 就用它（add=加群，invite=邀请）。
# 清单键格式：pending:friend:<flag> / pending:group:<flag>，字段名与「申请审批」钩子的记录一致。
# 占位符：{i} 序号 {n} 条数 {type} friend|group {qq} QQ号 {group} 群号 {flag} 申请标识
#   {subtype} add|invite {comment} 验证信息 {source} kv|hook|live {keys} 用到的钩子键 {key} 清单键
#   {op} op 名 {ops} 可用 op 清单 {k} 缺的参数名 {err} 失败原因 {deny} 基板权限文案
