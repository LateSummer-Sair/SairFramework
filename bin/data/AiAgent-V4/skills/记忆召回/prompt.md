# 记忆召回 · 文案与阈值
# 本文件是本插件唯一的配置源：条数、门槛、预算、行模板、标题全在这里。
# 格式：`键: 值` 一行一条；`#` 开头的行是注释（`## 配置` 这种分节标题也是注释）；
#       行内第一个冒号（半角 `:` 或全角 `：`）是分隔符，值里可以再带冒号（如时间格式）。
# 读法：惰性缓存 + 按文件 mtime 失效 —— 每轮不读盘，改完保存下一轮生效，不需要重启。
# Java 里只留「文件读不到 / host() 为 null 时的兜底默认值」，兜底值与下面前几行一一对应。
# 值两端的空格会被去掉；需要尾随空格的模板由 Java 侧补（见「行模板」说明）。

## 配置
开关: true
条数: 8
跨群条数: 5
最小查询字数: 4
查询过滤正则: [\s\p{Punct}]+
预算: 1200
单条上限: 300
跨群单条上限: 160
跨群开关: true
时间格式: yyyy-MM-dd HH:mm
截断尾巴: ...

## 文案
标题: ## 相关记忆
说明: 以前记住的、可能和这次有关的信息：
行模板: - [{ts}] {content}
跨群标题: ## 跨群相关群聊记录
跨群说明:
跨群行模板: - [群{group}] {nickname}: {content}
匿名昵称: 某人

## 备注（不解析，只给人看）
# 开关：false = 本段这一轮不出现（关掉整个召回，不查库）。
# 条数：查 memory 库取回的条数（V3 是 8 —— memories 表总量很小，多取几条不显著增加 token，
#   但明显提高命中率，见 QQPromptBuilder.java:693-695 的注释）。
# 跨群条数：查 grouplog（群聊历史）取回的条数（V3 是 5，QQPromptBuilder.java:706）。
# 最小查询字数：查询门槛。把「查询过滤正则」命中的字符全去掉之后，剩下的长度达到这个数才检索
#   —— 短语气词（"嗯""哈哈""在吗"）不触发检索（V3 hasMeaningfulQuery，QQPromptBuilder.java:719-722）。
# 查询过滤正则：默认 [\s\p{Punct}]+ 是 V3 原样（只去空白与 ASCII 标点，全角标点不去）。
#   想连全角标点一起去，可改成：[\s\p{Punct}\u3000-\u303F\uFF00-\uFFEF]+（这会偏离 V3 口径）。
# 预算：本段的字符上限，也是 maxChars() 的返回值（默认 1200，与 ContextProvider 的出厂默认同值）。
#   注意：maxChars() 拿不到 Host，所以它用的是「上一次读到的预算」，首次调用前是 1200。
# 单条上限：单条记忆渲染前最多保留多少字（V3 是 300，QQPromptBuilder.java:700）。
# 跨群单条上限：跨群行里的内容最多保留多少字（V3 是 160，UnifiedQQMemoryManager.java:798）。
# 跨群开关：false = 不查 grouplog、不产出跨群那一段。
# 时间格式：{ts} 的格式（ts 是毫秒时间戳）；ts 缺失时 {ts} 渲染成空串。
# 截断尾巴：超长截断后补的字符串（V3 是 "..."）。
# 匿名昵称：跨群行里昵称为空时的替代（V3 用「某人」，UnifiedQQMemoryManager.java:799）。

# 行模板可用占位符（{键} 字面替换，模板里带 $ 也不怕）：
#   memory 行：{ts} {content} {id} {importance} {scope} {scope_id} {source} {tags}
#   grouplog 行：{ts} {content} {id} {group} {qq} {nickname} {name}
#   {name} 与 {nickname} 同值；两个库都有的键（如 {ts}/{content}/{id}）两边都能用。
# 标题 / 跨群标题：整段的小标题（V3 走 labels：title.related-memories=相关记忆、
#   title.cross-group-related=跨群相关群聊记录，PromptManager.heading 会补上 "## "）。
#   留空 = 不打这一行（V3 里标题为空时就不打，QQPromptBuilder.java:698）。
# 说明 / 跨群说明：标题下面的一句引导语（V3 的 memories-ctx.md 第二行是英文
#   "Previously remembered information that may be relevant:"，这里按 S4 的默认做法用中文）。
