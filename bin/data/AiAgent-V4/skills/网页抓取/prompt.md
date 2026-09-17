# 网页抓取 · 文案
# 本文件是本技能唯一的文案源：面向模型/用户的每一句话都在这里。
# 格式：一行一条「键: 值」；# 开头的行是注释；行内第一个冒号（或全角冒号）是分隔符，值里可以再带冒号。
# 缺键 = 技能明确回「prompt.md missing text: 键名」，不会用兜底话术糊过去（这里没有兜底）。
# 值里可以用 \n 表示换行。

## 文案
# 拒绝内网：安全闸门（基板 kit/NetGuard）拦下时的说明。{host} 主机、{reason} 类别
#   （loopback/private/link_local/metadata/unique_local/cgnat/reserved/multicast/unspecified/name）、
#   {code} 机器码（internal=内网 / scheme=协议不支持 / bad_url=地址解析不了）、{url} 实际地址。
拒绝内网: [web] 这个地址指向内网（{host} · {reason}），按安全口径不抓：{url}
# 拒绝协议：不是 http/https 的协议（file:// ftp:// data: …）。
拒绝协议: [web] 只支持 http/https 地址：{url}
# 重定向异常：跳转链没走通（redirect_no_location=3xx 但没有 Location / too_many_redirects=跳数超限）。
重定向异常: [web] 跳转没走通（{reason}），最后停在：{url}
# 抓取失败：网络错或非 2xx。{error} 是结构性错误串（HTTP 404 / SocketTimeoutException…）。
抓取失败: [web] 抓取失败：{error}（GET {url}）
# 空正文：拿到了内容但去标签后没内容。{bytes} 解码后的字符数（不是原始字节数）。
空正文: [web] 抓到了 {bytes} 字符，但正文是空的（可能是 JS 动态渲染或被挡）。可以试 raw: true 看原始内容。
# 结果头：正常返回的第一行。{url} 最终地址（跟着重定向走之后）、{code} HTTP 状态、
#   {bytes} 解码后的字符数（代码传的是 Http.Res.body 的 length()；占位符名与 Java 绑定、别改）、
#   {charset} 实际解码用的编码。
结果头: GET {url}（HTTP {code}，{bytes} 字符，编码 {charset}）
# 截断尾巴：正文超过 max 时接在后面的说明。{total} 是截断前的字符数。
截断尾巴: \n…（已截断，原始 {total} 字符）
