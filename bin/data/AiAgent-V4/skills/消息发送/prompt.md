# 消息发送 · 配置与文案
# 本文件是本技能的唯一配置源（每次调用重读，保存即生效）。
# 格式：一行一条「键: 值」；# 开头的行是注释；缺项 = 该项不生效（Java 里只有结构性兜底，没有写死的用户文案）。
# 配置与文案分成两节，节名就是下面的 "## 配置" 与 "## 文案"。

## 配置
最多转述条数: 10
撤回最多条数: 20
撤回间隔毫秒: 300
撤回默认时间窗秒: 120
撤回历史条数: 50
时间格式: MM-dd HH:mm
转述头: 转述 {from} 的 {count} 条消息：
转述行: {time} {name}({qq})：{text}
转述嵌套行: {time} {name}({qq})：{text}
出处缺省: 上文
转述分隔: 换行
占位图片: [图片]
占位语音: [语音]
占位表情: [表情]
占位文件: [文件]
占位视频: [视频]
占位卡片: [卡片]
占位合并转发: [合并转发]
占位@: @{qq}

## 文案
群发成功: 已发到群 {group}（{chars} 字{at}）
点名: ，点名 {names}
私聊成功: 已私聊发给 {qq}（{chars} 字）
发送失败: {op} 没发出去（平台回执：{error}）。语音可用 record、文件可用 fileto 重试，别把失败当成功。
文件成功: 已把「{name}」发给 {target}
语音成功: 已把语音发给 {target}
语音失败: 语音没发出去（平台回执：{error}）。语音走的是 send_private_msg / send_group_msg 的 record 消息段，NapCat 得能把这段音频当语音处理（通常是它能转的格式，mp3 之类不一定吃；账号被风控也发不出）。要稳就改用 fileto 把文件直接发过去。
语音风控: 语音没发出去 —— can_send_record 说这个账号现在发不了语音（风控证据：{error}）。改用 fileto 把文件发过去更稳。
转告成功: 已把话转告给 {qq}
转告正文: {from}托我转告你：{text}
转告正文无出处: 转告你一句：{text}
转述成功: 已把 {count} 条消息整理成文本发到 {target}
转述部分失败: （另有 {count} 条取不回：{detail}）
转述全失败: 这几条消息都取不回来，没有发出去：{detail}
转述失败行: 第 {id} 条取不回（{error}）
撤回成功: 已撤回 {count} 条（{target}）
撤回部分: 已撤回 {count} 条（{target}），另有 {miss} 条没撤掉
撤回全失败: 一条都没撤掉（请求 {requested} 条，{target}）
撤回范围空: {target} 的最近 {scanned} 条历史里，{span}没有我自己发的消息，没得撤
撤回汇总: 撤回汇总 requested={requested} recalled={recalled} failed={failed} skipped={skipped} others={others} capped={capped}
撤回失败行: {id} 撤不掉：{error}
撤回跳过行: {id} 跳过：{why}
撤回超上限: 这次最多撤 {max} 条，还有 {rest} 条没撤 —— 再调一次（同样的范围，或者带上 last）就接着撤
撤回窗外: 另有 {count} 条我自己发的消息不在时间窗（{span}）里，我没撤；要撤就调大 within，或者给 since/until
撤回历史截断: （历史只看了最近 {count} 条，更早的要撤得用 message_ids 点名）
撤回历史无消息: （历史接口的返回里没认出消息，原始返回：{raw}）
撤回拉不到历史: 拉不到历史消息，撤不了：{error}
撤回别人不碰: （历史里另有 {count} 条不是我发的（{ids}），一条都没碰）
撤回未核实: 这些我没能核实是不是我自己发的，按你给的 id 撤了：{detail}
撤回无法核实: 没能核实这几条是不是我自己发的（{error}）—— 按你给的 message_ids 直接撤了
撤回读不到自己: 读不到自己的 QQ 号（conf.selfId() 是 0）
撤回认不出自己: 我读不到自己的 QQ 号（conf.selfId() 是 0），认不出历史里哪几条是我发的 —— 请直接给 message_ids
撤回认不出发送者: get_msg 没给出 user_id
撤回发送者未知: 别人
只能撤自己: 只能撤我自己发的消息 —— 这条是 {qq} 发的，我自己的 QQ 是 {self}；别人的消息我不撤，哪怕是管理员也不撤
撤回缺会话: 不知道去哪个会话里找消息 —— 没给 scope，当前会话也不是 QQ 会话（本地控制台）。请给 scope（group:群号 或 user:QQ号）；已经有 message_id 的话，直接给 message_ids 也能撤
撤回缺范围: 要撤哪些消息？给我 message_ids（明确的消息 ID，一个或多个），或者 last（最近几条我自己发的，默认 1）+ within（时间窗秒数，默认 120）—— 三个范围至少给一个
撤回scope看不懂: scope 看不懂：{scope} —— 要写成 group:群号 或 user:QQ号
撤回目标未指定: 未指定会话（按你给的 message_ids 撤）
撤回窗最近: 最近 {sec} 秒内
撤回窗区间: {from} 到 {to}
撤回窗起点不限: 最早
撤回窗终点现在: 现在
缺正文: 要发什么内容？把 text 给我。
缺目标群: 要发到哪个群？给我群号（group），或者在有群的会话里说。
缺目标人: 要发给谁？给我 QQ 号（qq）。
缺文件: 要发哪个文件？把 file 给我（本地路径、http 链接或 NapCat 的 fileid）。
缺消息ID: 要转述哪几条消息？把 ids 给我（message_id，逗号分隔）。
缺会话: file 只发给当前会话，而当前会话不是 QQ 会话（本地控制台）。要发给指定的人请用 fileto（带 qq），发语音请用 record（带 qq 或 group）。
缺收件人: 没给收件人。控制台不是 QQ 会话，认不出目标 —— 请给 qq（发给某人）或 group（发到某群）。
落点不是当前会话: 这个文件只会发在她和你正在说话的那个会话里（{now}），不能改送到 {want}。要发到别处，请在那个会话里再说一次。
只发自己的私聊: 东西只发在群文件和她正在说话的私聊里，不能从一个私聊送到另一个私聊去 —— 那样会打扰到别人。要发到群里，就在群里说一声。
不支持op: 不支持的 op：{op}（只支持 group / private / file / fileto / record / forward / relay / recall）。
异常: 执行异常：{error}
未知结果: Napcat 没有返回可识别结果
目标群: 群 {group}
目标人: {qq}

# 说明
# 最多转述条数：forward 一次最多取几条历史消息（上限 30）。
# 撤回最多条数：recall 一次最多撤几条（上限 100）。超了不报错 —— 撤到上限，回执里说"还有 N 条没撤"。
# 撤回间隔毫秒：recall 条与条之间睡多久（默认 300ms，0 = 不睡），用来避开平台风控。
# 撤回默认时间窗秒：recall 给了 last（或按范围撤）但没给 within/since/until 时用的时间窗（默认 120 秒）。
#   recall 的 last 与时间窗是"都要满足"：last 只数条数、时间窗只圈时间；只给时间窗不填 last 时，
#   取窗口内她自己发的全部（条数由"撤回最多条数"兜底，超出的在回执里说"还有 N 条没撤"）。
# 撤回历史条数：recall 按范围撤时一次拉多少条历史（默认 50，上限 100；实际取 max(它, last*3)）。
# 撤回汇总：recall 回执最后那一行的结构化口径 —— requested=这次要撤几条 recalled=真撤掉几条 failed=撤不掉的条数
#   skipped=点名里跳过的不属于自己的条数 others=历史里别人发的（一条没碰）capped=超单次上限没撤的条数。
# 时间格式：forward 转述行里 {time} 的格式，按 Java SimpleDateFormat 写。
# 出处缺省：from 没给时写在转述头里的出处。
# 转述分隔：换行 = 每条一行；空行 = 每条之间空一行。
# 占位*：图片/语音一类的消息段只留占位符，不下载也不转存。
# 表格里没有的键就是"这项没有文案"，技能会退回结构性兜底（例如 op=xxx error=yyy）。
# 动作名一律以基板 Api.catalog() 为准（模型侧看 napcat 工具的 action=list），禁止猜：
#   本机版本目录里没有 send_record / send_group_record / send_private_record —— 语音 = send_private_msg / send_group_msg 的 record 消息段；
#   文件 = upload_group_file / upload_private_file（也没有 send_*_file 这类别名）。
#   猜错动作名会被 NapCat 回 retcode=1404「不支持的Api」，白绕一圈 —— 发语音/发文件都从本技能走，别去 napcat 工具里手拼动作。
