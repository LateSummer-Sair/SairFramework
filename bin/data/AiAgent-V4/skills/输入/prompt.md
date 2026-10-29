# 输入模拟技能自己的配置文件（技能每轮开跑前读一次；只认「键: 值」一行一条，# 开头的整行是注释）。
总闸: on
每分钟上限: 60
# 黑屏自救（op=recover）用的计划任务名。
# 那个任务必须以【主人自己】为属主、RunLevel=Highest 注册 —— 这样技能用普通权限就能触发一次提权执行，
# 既不需要 UAC 弹窗、也不需要 SeTcbPrivilege、更不暴露 SYSTEM。
进桌面任务: AiAgent-RDP-to-Console-Now
