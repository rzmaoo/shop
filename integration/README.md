# InoriLoot 运行时兼容测试

这是独立的 Forge 测试模组，加载后在服务端启动完成时运行断言，写入 `compat-test-results.txt` 并自动停止服务器。仅放入独立测试服，不能部署到正式服务器。

## 构建

在项目根目录运行：

```powershell
.\gradlew.bat build compatTestJar -I integration/build-tests.gradle
```

正式产物在 `build/libs/`，测试模组为 `build/compat-tests/shop-compat-tests.jar`。普通 `build` 不编译或打包此测试模组。

## 环境

准备独立的 Mohist 1.20.1 / Java 17 实例，使用独立世界，监听 `127.0.0.1` 和不占用正式服的端口。根据服务端许可要求准备 EULA 文件。复制 `integration/economy.toml` 到该实例的 `config/shop/economy.toml`，测试依赖其中的固定价格。

先只在 `mods` 中放入正式 `shop-1.2.jar` 和 `shop-compat-tests.jar`，验证未安装 InoriLoot 的行为。服务器停止后保存报告与日志。

然后在 `mods` 增加 InoriLoot Forge 1.0.0 和 Item Rarity 1.2.0，在 `plugins` 增加提供的 InoriLoot 1.0.0 Bukkit 插件，再次启动。插件首次加载可能下载 TabooLib 等依赖，需要网络或完整缓存。

报告必须以 `ALL PASSED` 结束。Java 进程的正常退出码本身不能证明测试通过；启动失败时也应先检查控制台输出和 `logs/latest.log`，不能误读上一次遗留的报告。

## 覆盖范围

测试通过内存网络连接创建真实 `ServerPlayer`，对提供的网格数据包执行编解码，再调用实际处理器，因此能验证生产混淆环境中的 Mixin 和反射接口。所有移动都检查托管区持久化结果；写入失败场景使用 SQLite 触发器制造真实异常，并检查物品回滚与重试。

覆盖物品 NBT、插件 ID 定价、占位物过滤、Bukkit 身份接口、网格从属格、尺寸边界、旋转、拖拽、堆叠、取回、ATM、旧托管区迁移及交易重复执行。不会启动图形客户端，不能替代真人联机后的视觉检查。

本次运行使用的隔离目录为 `build/inoriloot-integration/`；结果留在 `build/compat-tests/without-inoriloot-results.txt`、`with-inoriloot-results.txt` 及对应 `.log` 文件中。具体版本、文件校验值和用户配置示例见 `docs/inoriloot-compatibility.md`。
