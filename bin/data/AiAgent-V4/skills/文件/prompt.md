# 文件（工具 file）· 配置与文案
# 本文件是本技能唯一的阈值与文案来源：技能每次调用都重读它 —— 改这里即时生效，不需要改 Java。
# 格式：一行一条「键: 值」；# 开头的行是注释；空行忽略。
# 值里的 \n 会还原成换行；{xxx} 是占位符，由技能按上下文渲染（没被替换的占位符会原样留下，便于发现写错）。
# 注意：本技能**没有隐藏默认值** —— 下面任何一个键删掉，对应路径都会明确报「缺键」而不是悄悄用一个 Java 里的数字。

## 配置（阈值，单位见注释）
最大读取字节: 4194304
最大扫描字节: 1048576
最多列出行数: 300
默认起始行: 1
默认读取行数: 200
最多返回行数: 2000
最多命中: 50
最多扫描文件: 3000
扫描深度: 6
下载超时毫秒: 120000
下载上限字节: 33554432
默认下载名: download.bin
写入上限字节: 1048576

## 文案（面向模型/用户；{...} 为占位符）
不支持op: [file] 不支持的 op：{op}（只支持 {ops}）
异常: [file] 操作失败（{op}）：{error}
路径非法: [file] 路径不合法（系统不接受这种写法，无法解析）：{path}
路径不存在: [file] 路径不存在：{path}
不存在: [file] 文件不存在：{path}
是目录: [file] {path} 是目录，用 op=list 列举，或用 op=find 找文件。
是文件不是目录: [file] {path} 是文件不是目录，用 op=read 读它。
不是普通文件: [file] 不是普通文件：{path}
目录读不了: [file] 目录读不了（权限或路径异常）：{path}
太大: [file] 文件太大（{size} 字节，上限 {limit}），先确认要读哪一段再动手。
读失败: [file] 读取失败（二进制、编码异常或没有读权限）：{path}
是二进制: [file] 看起来是二进制文件（含 NUL 字节），不支持阅读：{path}
空文件: 文件 {path}（0 行，空文件）
读头: 文件 {path}（共 {total} 行，显示 {from}-{to}）
行号: {no}| {text}
读还有: …（还有 {rest} 行，用 offset={next} 继续读）
偏移越界: [file] offset={offset} 超出范围：{path} 只有 {total} 行。
目录头: 目录 {path}（{count} 项）
目录项目录: [D] {name}/
目录项文件: [F] {name}  {size} 字节  {time}
目录还有: …（还有 {rest} 项未列出）
查找无果: 在 {path} 下（扫了 {scanned} 个文件）没找到匹配「{keyword}」的文件。
查找头: 在 {path} 下找到 {count} 个匹配「{keyword}」（扫了 {scanned} 个文件）
命中文件名: [F] {path}（文件名命中）
命中内容: [F] {path}（命中内容 第 {line} 行：{snippet}）
下载地址非法: [file] 只支持 http/https 下载：{url}
下载拒绝内网: [file] 这个下载地址指向内网（{host} · {reason}），按安全口径拒绝：{url}
下载跳转异常: [file] 下载的跳转没走通（{reason}），最后停在：{url}
下载越界: [file] 拒绝访问：下载只能落在数据目录的 files/ 里面：{path}
下载前: [file] 下载前确认：{url} → {path}（上限 {limit} 字节）
下载成功: 已下载 {path}（{size} 字节）← {url}
下载失败: [file] 下载失败：{url}（{error}）
下载超限: [file] 下载超过上限（{size} > {limit} 字节），已删除：{path}
写入是目录: [file] {path} 是目录，不能写。
写入超限: [file] 正文太大（{size} 字节，上限 {limit}），先拆小再写。
写入前: [file] 写入前确认：{path} 将要写 {size} 字节（原文件 {old} 字节）
写入成功: 已写入 {path}（{size} 字节，{lines} 行；原文件 {old} 字节）
写入失败: [file] 写入失败：{path}

## 说明
# 能碰什么 = **唯一权威是技能管控表（op）**：技能不自己设"路径必须落在数据根以内"的上限 —— 相对路径按 conf.root()
#   解析（那是"相对于谁"的语义），绝对路径原样采用；于是数据根以外的文件（例如 D:\Share\**\x.mp3）
#   也进得来，只要那个 op 被放行。五个动作各判自己的 op：file.read / file.list / file.find /
#   file.download / file.write（每个动作分支开头判一次）。判定看 data/skillctl.json：Run 白名单 = 可用、
#   Ban 黑名单 = 不可用、空 = 未授权 = 不可用（Ban > Run > 空）；MASTER 与她本人 SYSTEM 恒全权。
#   **被拒时回执就是拒绝原文**（形如「[权限阻断] 「file.write」没有授权给你（未授权 = 不可用）」）——
#   不要在这里另造一句，调用方要靠它判断要不要换别的工具。事实块 caller.ops 就是授权清单。
# 结构性防护仍在（这些不是权限）：① getCanonicalFile() 抵掉 .. 与符号链接；② toRealPath() 把目录联接
#   （junction）解成真实位置 —— 别名（软链/junction）骗不过这两道；find/list 的扫描
#   条数与深度上限也照旧。路径写法系统不接受时用上面的「路径非法」文案（这是输入不合法，不是权限拒绝）。
# download：只下 http/https，落点固定在数据目录的 files/ 里，落点参数仍受 canonical 前缀 + 真身前缀两道校验
#   （「下载越界」）—— 那是这个 op 的语义（落点必须在 files/），不是权限；能不能下载已由分支开头的
#   h.need("file.download") 判过。
#   下载走基板的带闸门入口 Http.download —— 环回/私网/链路本地/云元数据一律拒绝，且每一跳重定向复检
#   （判定本体在 kit/NetGuard，技能改不动）；被拒时用上面的「下载拒绝内网」文案。
#   跳转链没走通（3xx 但没有 Location / 超过 5 跳）用「下载跳转异常」。
# write：判这个动作自己的 op（FileSkill 的 write 分支开头 h.need("file.write")），判不过就把拒绝原文原样回给调用方。
#   写前把字节数写进控制台日志再落盘。
# read/find 的文本解码：基板 kit/Text 自动探测（BOM → 严格 UTF-8 → GBK → GB18030 → UTF-8 兜底），
#   GBK/ANSI 的 txt 不再整篇乱码；控制台会打一行「按 GBK 解码：路径」。
