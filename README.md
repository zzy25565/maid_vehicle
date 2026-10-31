# Maid Vehicle Control (车万女仆 × 卓越前线 载具适配)

一个 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）与
[Superb Warfare](https://github.com/Mercurows/SuperbWarfare)（卓越前线）的适配 addon：
给女仆任务栏新增 **"载具操控"** 任务，让女仆独立驾驶载具跟随主人、操作主炮索敌开火，
并在主人上车后按优先级乘坐载具、操作副炮。

## 功能

- **驾驶跟随**：女仆走上最近的可用载具并成为驾驶员，追着主人开（含转向死区、减速、卡住倒车脱困）
- **主炮自主攻击**：驾驶员位的女仆会索敌并转塔开火——弹道提前量解算与开火循环由 SBW 原生的
  Mob 载具武器系统完成，本 mod 只负责把目标喂给女仆
- **选车优先级**（主人上车后）：
  1. 空载具（女仆自己驾驶）
  2. 主人载具（副炮位空且有武器）
  3. 其他女仆驾驶的载具（副炮位空且有武器）
  4. 主人载具（搭车）
  同优先级内：血量高者优先，其次距离近者优先
- **副炮位**：乘坐 2/3 优先级载具时自动换到武器站控制位并索敌开火
- 第一版仅开放地面载具（轮式 / 履带 / 船 / 轮椅），飞行器驾驶计划后续支持

## 依赖

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Superb Warfare | 0.8.9.1+ |
| Touhou Little Maid | 1.5.3+ |

服务端与客户端都需要安装。

## 工作原理

- SBW 的驾驶输入是服务端公开方法 `VehicleEntity#processInput(short)`（9 位按键掩码），
  AI 直接调用即可驱动载具，输入经 SynchedEntityData 自动同步到客户端；
- SBW 对载具上的非玩家（Mob）乘客原生支持自动瞄准与开火：
  Mob `setTarget` 触发的 `LivingChangeTargetEvent` 会同步目标 UUID，
  引擎每 tick 弹道解算伺服转塔，对准 4° 内按 RPM 自动开火；
- 本 mod 实现的 `IMaidTask` 通过 TLM 的 `@LittleMaidExtension` 扩展点注册进女仆任务栏，
  驾驶行为同时挂在工作与骑乘两套大脑活动列表（TLM 骑乘时切换活动）。

## 构建

纯离线 javac 构建（不依赖 moddev/NFRT）：

```bash
# 将以下 jar 放入 libs/：
#   minecraft-neoforge-compiled-<ver>.jar  (NFRT 缓存中的 compiledWithNeoForge_*_output.jar)
#   superbwarfare-<ver>.jar                (生产 jar，mojmap 命名可直接编译)
#   touhoulittlemaid-<ver>.jar             (生产 jar)
#   以及 FML loader/bus/modlauncher/annotations/mergetool-api/kotlin-stdlib 等
gradle jar
```

libs 目录不随仓库分发（第三方二进制，请自行从对应 mod 的发布渠道获取）。

## 许可

GPL-3.0（因运行时链接 Superb Warfare，其采用 GPL-3.0）。
Touhou Little Maid 代码为 MIT，本仓库未包含其源码或二进制。
