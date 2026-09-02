# InoriLoot 多格物品兼容

shop 在服务端检测到 InoriLoot Forge 后自动启用网格兼容，不需要额外开关，也不需要修改 InoriLoot 插件或模组。

## 安装

- Mohist 服务端使用 `build/libs/shop-1.2.jar`，替换原 shop JAR。
- 服务端 `mods` 安装 InoriLoot Forge 和 Item Rarity；`plugins` 安装 InoriLoot Bukkit 插件。
- 客户端安装相同版本的 InoriLoot Forge 和它的 Item Rarity 前置，使用 InoriLoot 原有的网格显示和操作。
- 重启后，服务端日志出现 `Shop InoriLoot compatibility enabled (grid protocol 2)` 表示兼容已启用。

本次针对以下文件验证：

| 组件 | 文件 | SHA-256 |
| --- | --- | --- |
| InoriLoot Forge | `InoriLoot-Forge-1.20.1-1.0.0 (7).jar` | `7AC1E65C8AED92CAA49C8FC398EE542AE33BB5058BC09FA718A5102BC9D463FF` |
| InoriLoot Bukkit | `InoriLoot-1.0.0 (8).jar` | `2FAFEC77EB7C2C441FA023C484E052D2FB1BAEEB9B2B45B4A5856EAD24920DF5` |
| Item Rarity | `ItemRarity-1.20.1-forge-1.2.0.jar` | `2E797F4903A78A4187A905C9E103A23C8267E5ED2A2BD2C4D09652A5DB71CF55` |

提供的 InoriLoot Forge 要求 `item_rarity` 版本范围 `[1.2.0,1.3)`。本次使用作者发布的 [Forge 1.20.1 / Item Rarity 1.2.0](https://www.curseforge.com/minecraft/mc-mods/item-rarity/files/7751782)，源码仓库为 [Scarasol/Item-Rarity](https://github.com/Scarasol/Item-Rarity)。兼容代码依赖所提供 JAR 的网格接口，升级 InoriLoot 后应重新验证。

## 单独定价

在服务端 `config/shop/economy.toml` 原有的 `prices` 数组中增加条目。例如，InoriLoot `items/default.yml` 中的物品 ID 为 `example_item`：

```toml
prices = [
  { item = "minecraft:paper", price = "1.00" },
  { item = "inoriloot:example_item", price = "12.50" },
]
```

插件物品使用 `PublicBukkitValues` 中的 `inoriloot:loot-item-id` 识别，配置 ID 使用小写。优先采用插件物品 ID 的价格；未单独定价时沿用基础材质的价格。没有任何匹配价格的物品仍不能出售。旋转、品质、模型和占用格数不改变单价，结算数量只计算实际物品堆叠。

ATM 继续使用原有 `atm.item` 作为实体货币，存入时可以携带多格信息；取款仍发放配置的基础物品。搜索中或被锁定的占位物品不能出售或存入 ATM。

## 网格与数据保护

- 出售区为 9 × 5，ATM 存入区为 9 × 4；控制按钮不属于网格，不会被多格物品覆盖。
- 支持 InoriLoot 的点击从属格、Shift 移动、左右键拆分、拖拽、双击收集、旋转和符合尺寸要求的快捷栏交换。
- 取回和 ATM 发放按真实矩形空间预检。从属格和零散空格不会被误算为可用空间，不能完整放入的物品继续保留在托管区。
- 网格数据包也经过 shop 的 SQLite 事务和玩家存档流程。数据库准备写入失败时，物品、鼠标携带物和背包一起恢复；已经提交的操作不会因界面同步失败而回退。
- 保留原有 NBT 序列化，包含插件 ID、尺寸、旋转、品质、自定义模型、名称及其他物品数据。关闭界面后，托管区及鼠标携带物可在下次打开时恢复。
- 商店内禁止丢弃、创造物品和复制物品。InoriLoot 的自动整理不能把托管物品丢到地上。
- 旧托管物品若能完整排入网格，会自动重新排列并持久化；放不下时保留全部物品，暂停向该托管区放入新物品，可通过出售或取回清理。恢复有效布局后自动恢复正常网格。

未安装 InoriLoot Forge 时，shop 继续按原有单格菜单工作；插件物品 ID 定价仍可独立读取。默认 Mohist 包不内嵌 SQLite，纯 Forge 使用保留 SQLite 的 `shop-1.2-standalone.jar`。

## 验证结果

2026-09-02，在独立测试世界中使用 Java 17.0.18、Mohist `1.20.1-46ca7304` / Forge 47.4.13，加载上述原始 JAR 与重新构建的 shop：

- Gradle 构建通过，现有 22 项单元测试通过。
- 不加载 InoriLoot 时，服务端完整启动，普通 Shift 移动、出售及防止重复结算通过。
- 同时加载插件、Forge 模组和前置时，服务端完整启动，插件真实身份接口读取通过。
- 网格移动、旋转、拖拽、拆分、双击收集、边界、快捷栏尺寸、关闭重开及出售通过。
- 满背包、零散空间、部分堆叠合并、取回保留和 ATM 存取通过。
- 注入 SQLite 写入失败后完整回滚，恢复数据库后重试成功。
- 旧托管区自动整理及超出容量时保留物品通过。

运行时测试使用实际服务端、生产 JAR、InoriLoot 数据包编解码及处理器、Mixin、SQLite 和玩家存档。客户端显示与点击区域已核对提供 JAR 的实现，但本次未进行真人客户端联机画面验收。复现方法见 `integration/README.md`；原始运行报告及日志位于 `build/compat-tests/`。
