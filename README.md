# Maid Vehicle Control (车万女仆 × 卓越前线 载具适配)

一个 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）与
[Superb Warfare](https://github.com/Mercurows/SuperbWarfare)（卓越前线）的适配 addon：
给女仆任务栏新增 **"载具操控"** 任务，让女仆独立驾驶载具跟随主人、操作主炮索敌开火，
并在主人上车后按优先级乘坐载具、操作副炮。

## 功能

- **驾驶跟随**：女仆走上最近的可用载具并成为驾驶员，追着主人开
- **巷道绕障**：二维栅格 A* 寻路——按载具宽度做障碍膨胀，规划可通行路径并沿航点行驶，
  专治基地巷道、城市道路这类直线上不去的复杂环境；无路可走时退回直线追踪，
  撞墙卡住自动倒车脱困（3 次失败原地休眠 10 秒）
- **履带 / 轮式分别适配**：SBW 的两种底盘物理完全不同——履带转向不依赖速度（可原地转向），
  轮式转向率正比于行驶速度（静止打方向无效）。驾驶策略按引擎类型分化：
  履带大角度误差时原地转正，轮式始终带油门边走边转并提前滑行减速，
  转向死区、停车距离、航点半径也各自调参
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
  驾驶行为同时挂在工作与骑乘两套大脑活动列表（TLM 骑乘时切换活动）；
- 驾驶分两层：**全局**用栅格 A*（`VehiclePathfinder`）在载具周围 24 格内规划绕障路径，
  采样方块碰撞形状并按载具包围盒宽度膨胀障碍，8 向搜索禁切角，路径做视线简化只留拐点；
  **局部**按"目标方位角误差 → 左右键、距离 → 油门"的纯追踪控制行驶。
  每 30 tick 或主人移动超 6 格时重规划，撞墙卡住则丢弃路径强制重算。

## PVP 阵营战接口（预留，默认关闭）

> **状态：接口已就位，功能未应用。** 本仓库不注册任何实现，运行时女仆的敌我识别与 1.0.0d
> 完全一致：只打 `Enemy` 怪物，以及最近攻击过主人 / 女仆自己的实体。**玩家永远不会被当作目标。**

为将来的阵营战玩法留了两个公开扩展点（包 `com.zzy.maidvehicle.api`）：

| 类 | 作用 |
|---|---|
| `FactionHostilityPolicy` | 策略接口：`isHostile(...)` 判敌对；`enableProactiveScan()` 是否允许主动索敌（默认 `false`） |
| `FactionHostility` | 注册门面：`register` / `clear` / `get` / `isEnabled`；`policy == null` 即关闭 |

钩子接在两处，缺一不可：

- `DriveVehicleBehavior#isValidTarget`：基础安全过滤之后追加一次策略询问 → 管「能不能打」和「反击」；
- `DriveVehicleBehavior#pickTarget` 的 AABB 主动扫描：候选谓词由 `e instanceof Enemy`
  扩展为 `e instanceof Enemy || FactionHostility.isProactiveCandidate(...)` → 管「会不会主动找玩家打」。

**基础安全过滤在策略之前已完成**，实现方不用重复：目标存活 / 非自己 / 非主人 / 非其他女仆 /
非同车 / `isAlliedTo(maid)` / `isAlliedTo(owner)`（scoreboard 同队玩家在这里已被挡掉）/ 在最大索敌距离内。
策略必须是纯判定、无副作用，会被高频调用。

### 将来要启用时怎么做（现在不要做）

1. 实现 `FactionHostilityPolicy`，用 scoreboard team / 自定义阵营数据判定敌对玩家；
2. 需要女仆主动清场才把 `enableProactiveScan()` 覆写为 `true`，否则只保留「被打才还手」，
   并自行处理出生点保护、中立玩家白名单；
3. 在自己的模组初始化阶段调用 `FactionHostility.register(...)`，卸载 / 关服时 `clear()`；
4. 双端同步部署，实机验证：同队不打、敌队打、误伤中立、驾驶员位与副炮位表现一致。

示例（**仅示意，本仓库未启用**）：

```java
FactionHostility.register(new FactionHostilityPolicy() {
    @Override
    public boolean isHostile(LivingEntity candidate, EntityMaid maid,
                             LivingEntity owner, VehicleEntity vehicle) {
        if (!(candidate instanceof Player)) return false;
        Team mine = owner.getTeam();
        Team theirs = candidate.getTeam();
        return mine != null && mine != theirs;
    }
    // 默认 false = 只反击，不主动索敌
});
```

> 局域网离线模式（`online-mode=false`）下玩家 UUID 由服务端按名字生成并保持稳定，
> 但改名会换 UUID；阵营数据若按 UUID 存储，需自行处理改名。

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
