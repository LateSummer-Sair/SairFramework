# 经验蒸馏 · 配置与提示词
# 本文件是「经验蒸馏」唯一的配置源：策略、阈值、开关、提技模板、给模型的提示词、面向用户/模型的文案全在这里。
# 保存即生效（技能每次运行都重读本文件，不需要重启）；Java 只留**读不到配置时的兜底值/兜底文案**，
# 阈值与口径一律以本文件为准（读不到本文件时才落回 Java 里那份兜底）。
# 格式：`键: 值` 一行一条；`#` 开头的行是注释；行内第一个冒号（或全角冒号）是分隔符，值里可以再带冒号。
# 只读 `## 配置` / `## 文案` / `## 提取提示词` / `## 提技模板` 四个分节；分节外的内容随便写，不会被解析。

## 配置
启用: true
间隔小时: 12
失败重试分钟: 10
取样条数: 40
最少条数: 4
最低重要度: 5
默认重要度: 5
自动提技: off
提技门槛: 8
提技最低命中: 1
列表条数: 15
检索条数: 5
每条最大字数: 200
查重最短长度: 8
相似阈值: 0.85
指纹最短项数: 4
温度: 0.3
经验标签: 经验
对话角色: 
提技技能名模板: 经验_{slug}
提技工具名模板: exp_{slug}
提技标记: 　〔已提技：{skill}〕
样本行对话: {time} 【{session}】{role}: {text}
样本行群聊: {time} 【群{group}】{name}: {text}

## 提取提示词
你在为「经验蒸馏」提炼可复用的经验。

素材是最近的对话（一问一答的成功回合）与群聊片段。请找出"以后遇到同类问题能直接用上"的东西：
做法、结论、踩过的坑、约定、可复用的步骤、判断依据。
不要提炼：一次性的寒暄与情绪、只对当时那件事成立的临时信息、无法复述的私人内容、别人的隐私。

输出要求（严格遵守）：
1. 只输出一个 JSON 数组，不要输出解释、不要包 Markdown 代码块；
2. 数组每个元素形如 {"title":"...","content":"...","tags":["..."],"importance":0-10,"skill_candidate":true/false}；
3. title 是 8-20 字的短标题（同一个做法要用同一个标题，便于去重）；
4. content 写清"什么情况 → 怎么做 → 为什么"，60-200 字，能照着做；
5. importance：9-10=通用且反复会用到，6-8=特定场景下有用，0-5=一次性或不确定；
6. skill_candidate：true 表示"这条经验已经稳定到值得做成一个专门技能"（纯事实性结论写 false）;
7. 没有值得记的就输出 []，不要为了凑数编造。

素材条数：{count}

素材：
{msgs}

## 提技模板
# 只在「自动提技: on」且这一条经验达标时使用。两个代码块分别渲染成技能 md 与技能 java。
# 占位符：**md 与 java 两个代码块支持的占位符不同** ——
#   ```md 块：{name} {tool} {id} {title} {tags} {content} 都会被替换（照上面这行写）。
#   ```java 块：代码里只替换 {id} {name} {tool} 三个；写 {title}/{tags}/{content} 会**原样留在生成的源码里**
#   （当前模板没用它们，所以暂无影响；要加就把 Java 侧 `fill(c.javaTpl, …)` 的参数一并补上）。
# 模板里不要出现 `## ` 开头的行（会被当成分节边界）。

```md
---
name: {name}
description: 由「经验蒸馏」自动提升的经验技能（经验 #{id}）：回放一条已经沉淀下来的经验
tool: {tool}
airun:
  description: 回放经验「{title}」。需要时也可以用 query 在经验库里再搜一下。
  returns: 这条经验的正文（实时从六库 note 读取，改了经验这里也跟着变）
  params:
    query:
      type: string
      description: 可选：换个关键词在经验库里搜（留空则回放本技能对应的那一条）
---
# {name}

这条技能由「经验蒸馏」从经验 #{id} 自动提升而来，只是把一条已经沉淀下来的经验重新讲清楚。
正文每次调用都从六库的 `note` 里实时读取，所以经验被更新后这里自动跟着更新；经验被删掉则如实说"已经不在了"。

标签：{tags}

经验正文：

{content}

删除方式：`skill_write {op: delete, name: {name}}`（需要主人）。
```

```java
import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;
import sair.v4.store.Store;

/**
 * 经验回放技能（由「经验蒸馏」自动提升生成，经验 #{id}）。
 * 只做查库与回话：读 note 库里的那一条经验，或按关键词搜经验库；
 * 不执行系统命令、不读写文件、不调用任何执行类能力。
 */
public class AirunSkill implements Skill {

    private static final String LIB = "note";
    private static final long NOTE_ID = {id}L;

    @Override
    public Object call(Args a, Host h) {
        Store s = h == null ? null : h.store();
        if (s == null) return "[experience] 拿不到六库接口，技能不可用。";
        String query = Str.trim(a.str("query"));
        if (!query.isEmpty()) {
            List<JsonObject> rows = s.search(LIB, query, 3);
            if (rows == null || rows.isEmpty()) return "[experience] 经验库里没有匹配「" + query + "」的内容。";
            StringBuilder sb = new StringBuilder();
            for (JsonObject r : rows) {
                sb.append("#").append(J.l(r, "id", 0L)).append(" ").append(J.s(r, "title", "")).append("\n");
                sb.append(J.s(r, "content", "")).append("\n\n");
            }
            return sb.toString().trim();
        }
        JsonObject row = s.get(LIB, NOTE_ID);
        if (row == null) return "[experience] 这条经验已经不在了（id #" + NOTE_ID + "）。";
        return J.s(row, "title", "") + "\n" + J.s(row, "content", "");
    }
}
```

## 文案
# 这些是技能面向用户/模型说出来的话；占位符由 Java 替换。键缺失时技能只说结构性结果（不会崩）。
文案-条目头: 经验库最近 {n} 条：
文案-条目头查: 经验库里匹配「{query}」的 {n} 条：
文案-条目行: {i}. #{id} [{importance}] {title}　命中 {hits}　标签：{tags}{mark}
文案-无条目: 经验库还是空的。
文案-没有匹配: 经验库里没有匹配「{query}」的内容。
文案-新增: 已记下经验 #{id}：{title}
文案-重复: 已存在同样的经验（#{id}），只更新了重要度与命中次数。
文案-未启用: 经验蒸馏没有启用（prompt.md 里的「启用」不是 true）。
文案-跳过节流: 距上一轮还没到间隔（还需 {wait} 毫秒），这一轮不动模型。
文案-样本不足: 最近只有 {sampled} 条素材，少于 {min} 条，这一轮跳过。
文案-模型失败: 模型调用失败（{error}），这一轮没有写库；闸门按「失败重试分钟」退避，下一轮会自动重试。
文案-解析失败: 模型返回的不是 JSON 数组，这一轮跳过（没写脏数据）。
文案-缺提示词: prompt.md 里缺少「## 提取提示词」分节，这一轮跳过（没提示词就不打模型）。
文案-单条结果: 取样 {sampled} 条 → 候选 {cand} 条，新增 {add}，更新 {upd}，重复 {dup}，低分丢弃 {low}，提技 {promoted} 个。
文案-提技成功: 已把经验 #{id}「{title}」提升成技能 {skill}（工具 {tool}），下一轮对话就能直接用。
文案-提技失败: 提技没成功（{reason}），这一条留到下一轮再试。
文案-已忘记: 已删除经验 #{id}（{title}）。
文案-没有这条: 经验库里没有 id={id} 的记录。

## 备注（不解析）
# 启用：不为 true 就整个技能不动作（工具 op 也会说"没有启用"）。
# 间隔小时：两次"真正跑起来的轮次"之间的最小间隔，支持小数（0 = 不节流）。
# 失败重试分钟：上一轮以模型失败/解析失败结束时用的短退避（不占长时间隔，也不至于每 20 秒重打）。
# 取样条数：dialog（最近的成功回合）取多少条 + grouplog 取多少条；按时间升序合并、内容去重。
# 最少条数：合并后不足这么多条就跳过本轮（不打模型）。
# 最低重要度：模型给的 importance 低于这个值直接丢弃。
# 默认重要度：手工 add 没带 importance 时用的值。
# 自动提技：默认 off。on 才可能把经验提升成真技能（还要过 提技门槛/提技最低命中 与 B 类写位）。
# 提技门槛：importance 门槛；提技最低命中：hits（被重复提到的次数）门槛。
# 提技技能名模板 / 提技工具名模板：{slug} 由标题净化而来；工具名必须是 ASCII（function calling 的限制）。
# 提技标记：条目行里"已提技"的尾巴，{skill} 是提升出来的技能名；留空则不打标记。
# 对话角色：dialog 里收哪些 role（逗号分隔；留空 = 全部，一问一答都收）；样本行格式用 样本行对话 / 样本行群聊。
# 经验落在 note 库：source=经验蒸馏、tags 含 经验标签；note 没有 importance 列，所以重要度/命中次数/是否已提技
#   都放在 extra（JSON 文本列）里，形如 {"importance":8,"hits":2,"skill_candidate":true,"promoted":true,"skill":"经验_xxx"}。
# 查重最短长度 / 相似阈值 / 指纹最短项数：三道查重的口径。
#   ① 标题归一化后相等、② 正文归一化后相等或一方包含另一方（短边 ≥ 查重最短长度）、
#   ③ 都不中时按基板 kit/Fingerprint 的中文 2-gram Jaccard 判近似（标题优先，其次正文；≥ 相似阈值即算同一条；
#      指纹项数不足 指纹最短项数 就不做近似判定）。命中就只累加 hits/抬高重要度，绝不重复堆条目。
