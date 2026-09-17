# 申请审批 · 白名单与通知文案
# 本文件是「申请审批」技能的配置源：保存即生效（基板监听技能目录，自动热重载）。
# 格式：一行一条「键: 值」；# 开头的行是注释；键缺失或值留空 = 该通知不发（技能里没有写死的兜底文案）。

启用: true
自动同意群: 
通知主人: false
好友通知: 主人，有人申请加好友：{name}（{qq}），验证信息「{comment}」。flag={flag} —— 我没有自动同意好友申请，要同意就用 napcat 动作 set_friend_add_request。
群邀请通知: 主人，{name}（{qq}）邀请我去群 {group}（{subtype}），不在白名单里。flag={flag} —— 要同意就用 napcat 动作 set_group_add_request。
自动同意通知: 群 {group} 在白名单里，我已自动同意（flag={flag}）。

# 说明
# 自动同意群：群号白名单，逗号分隔，例如「123456,234567」；留空 = 任何群邀请都不自动同意（默认最保守）。
# 通知主人：出厂 false = **默认不通知**（避免陌生人加好友/入群申请就直接私信主人）；
#   记录照旧写 kv（approve_*_last / approve_*_count / pending:*），要用就把它改成 true。
# 占位符：{name} 申请人昵称、{qq} QQ号、{group} 群号、{subtype} add/invite、{flag} 请求标识、{comment} 验证信息。
