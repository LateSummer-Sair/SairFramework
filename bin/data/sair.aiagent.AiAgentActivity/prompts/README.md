# 提示词（全部外置，不打包进 jar）

这三份文件是插件的**全部提示词**，Java 里不再保留任何提示词正文。

| 文件 | 用在哪 | 拼装方式 |
|---|---|---|
| `systemPrompt.md` | 控制台聊天 / 本地 Agent 的底座 | 本地 Agent 提示词 = 本文件 + `\n\n` + `agentPrompt.md` |
| `execqPrompt.md` | QQ 通道（execq / execs） | 直接作为 QQ 通道提示词 |
| `agentPrompt.md` | execs/本地 Agent 追加段 | 含 `{os}` / `{shell}` / `{jdk}` / `{plugins}` 占位符，运行时替换 |

## 安装

把这三份文件拷进插件数据目录的 `prompts/` 子目录（**不是** plugins 目录）：

```
systemPrompt.md   →   <SFW>/data/sair.aiagent.AiAgentActivity/prompts/systemPrompt.md
execqPrompt.md    →   <SFW>/data/sair.aiagent.AiAgentActivity/prompts/execqPrompt.md
agentPrompt.md    →   <SFW>/data/sair.aiagent.AiAgentActivity/prompts/agentPrompt.md
```

- 读取顺序：**先看 `prompts/` 子目录**，找不到才回落到数据目录根下的同名文件（兼容旧布局）；
  两处都有时以 `prompts/` 那份为准，并在日志里点一句 —— 避免「改了根目录那份怎么不生效」。
- **改文件即热重载**（约 0.5 秒节流），不用重启、不用重编译。
- **文件不存在 = 该提示词为空字符串**，插件不会报错、也不会报警 —— 想要提示词就必须放文件。
- 这三份是"内容与改造前完全一致"的版本（已用 SHA-256 校验）：
  - `systemPrompt.md` → 1234 字符 / `a207f320780ee68e39c492982f29641c`
  - `execqPrompt.md` → 5215 字符 / `9821c0f8f97a80b2b8703f77e9e57e72`
  - `agentPrompt.md` → 1012 字符 / `7549610e40fa79145105aa7d1b4d70d7`
  （上表是**剥离标记行之后**的内容；自己改完想核对，把标记行删掉再算哈希即可。）

## 标记行（可选，但推荐保留）

```
<!-- AiAgent:base -->      基础规则的起点
<!-- AiAgent:persona -->   「角色设定」段的起点
```

- 这两行**在加载时会被剥离**，不会出现在发给模型的提示词里；
- 它们只做两件事：让 `ai/setprompt`、`ai/onebotsetprompt`、`editprompt` 能**只替换「角色设定」段**、
  把上面的规则原样保留；以及让文件结构一目了然。
- 删掉它们也能用：那时整份文件就是提示词，`setprompt` 会在文件末尾补一个带标记的角色设定段。

## 修改建议

- **改人格/性格/说话习惯** → 改 `## 角色设定` 段（或用 `ai/setprompt` / `ai/onesetprompt`，等价）。
- **改规则**（知识获取优先级、权限铁律、写作铁律…）→ 直接编辑 `<!-- AiAgent:base -->` 下面的正文。
- QQ 与本地是**两套独立提示词**，改一边不影响另一边。
