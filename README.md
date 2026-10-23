# 连锁挖矿(Chain Break)

一个 Minecraft **Fabric** 模组，按住激活键（默认 `` ` ``）挖掘即可一次性挖掉相连的方块，对齐 FTB Ultimine 的核心体验。

## 环境要求

| 项目            | 版本                                |
| ------------- | --------------------------------- |
| Minecraft     | **26.4 Snapshot**（兼容 26.3 ~ 26.4） |
| Fabric Loader | **0.19.5** 及以上                    |
| Fabric API    | **0.140.0** 及以上                   |
| Java          | **25** 及以上                        |

## 特性

- **6 种形状**：无形状 / 小型通道 / 大型通道 / 小型方形 / 逃生通道 / 采矿通道
- **形状菜单**：按住 `` ` `` 在屏幕左上角显示面板，`` ` `` + Shift 展开形状列表，Shift + 鼠标滚轮切换
- **实时预估**：面板显示准星指向的方块名、当前形状将破坏的方块数、以及最低工具要求（✓/✗）
- **掉落方式**：原地掉落 / 直接进背包 / 掉到玩家脚下（可配置）
- **FTB Library 风格配置界面**：背包 / 创造物品栏左上角 ⚙ 按钮，或按 `K` 打开
- **完整配置项**：饥饿消耗、经验消耗、冷却、工具耐久保护、方块黑名单、触发方式等
- **`/chainbreak` 命令**：游戏内查看 / 修改配置

## 按键

| 按键              | 功能            |
| --------------- | ------------- |
| `` ` ``（默认）     | 启用连锁挖矿 / 显示面板 |
| `` ` `` + Shift | 展开形状菜单        |
| Shift + 滚轮      | 切换形状          |
| `K`             | 打开配置界面        |
| `[` / `]`       | 切换形状（备用）      |

## 构建

26.x 的 Minecraft jar 已不再混淆，无需 Gradle / Loom，直接对官方 jar 编译即可。

```bash
# 准备依赖（client2645.jar、mclibs、fapi、mixin、fabric-loader-stub）
# 见 build.sh 中的 DEPS 路径

./build.sh
# 产物输出到 dist/chainbreak-<version>+mc26.4.jar
```

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（0.19.5+）和 [Fabric API](https://modrinth.com/mod/fabric-api)（0.140.0+）；
2. 将构建产物放入实例的 `mods/` 目录；
3. 启动游戏。

## 许可证

[MIT](LICENSE)
