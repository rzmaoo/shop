package com.rzmao.shop.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.rzmao.shop.money.Money;
import com.rzmao.shop.compat.InoriLootItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

public final class EconomyConfig {
    public static final long DEFAULT_MAX_BALANCE = Money.parse("1000000000000.00", false);
    static final String DEFAULT_CONFIG = """
            # Author @RZMAO
            
            # 商店 Mod 经济配置文件
            #
            # 注意：配置文件中的金额必须大于0，并最多可以保留两位小数
            # 玩家指令：
            #   /shop：打开出售界面
            #   /atm：打开兑换界面
            # 管理员指令：
            #   /shop reload：重载配置文件，如果重载失败，旧配置会继续生效
            #   /shop balance <玩家名或UUID>：查询玩家信息
            #   /shop logs <玩家名或UUID> [页码]：查询玩家日志
            #   /shop logs <玩家名或UUID> <开始时间> <结束时间> [页码]：按时间查询日志。
            #     时间格式：2026-06-01 或 2026-06-0100:00:00，时区使用 logTimeZone。
            #
            
            # 配置版本，请不要修改
            schemaVersion = 1
            
            # 单个玩家允许持有最大虚拟币余额
            maxBalance = "1000000000000.00"

            # 日志显示、/shop logs 时间筛选、备份文件名使用的时区
            # e.g.
            #   "Asia/Shanghai"  中国时间（默认）
            #   "UTC"  世界协调时间
            #   "America/New_York" 纽约时间
            logTimeZone = "Asia/Shanghai"

            # 商店价格表
            #
            # 注意：
            #   1. 不能配置 air 等不存在的物品，同一个物品不能重复配置
            #   2. 价格不能大于 maxBalance
            #   3. 如果把ATM实体货币也加进去，出售价格不能高于 atm.valuePerItem，否则会无限套利
            #   4. InoriLoot 插件物品可用 inoriloot:<items 配置中的 ID> 单独定价，优先于原版材质价格。
            #      未单独定价时沿用材质价格；搜索中或锁定的占位物品不能出售、存入 ATM。
            #      多格兼容自动启用，无需增加开关，物品旋转不影响售价。
            prices = [
              # { item = "minecraft:diamond", price = "25.00" },
              # { item = "minecraft:emerald", price = "10.00" },
              # { item = "inoriloot:example_item", price = "12.50" },
            ]

            # 操作反馈音效（必须填写原版或已安装 Mod 注册的声音事件 ID）
            # 修改后可使用 /shop reload 立即生效
            [sounds]
            # 出售成功时
            sellSuccess = "minecraft:entity.player.levelup"
            # 出售失败时
            sellFailed = "minecraft:entity.villager.no"
            # ATM 存入成功时
            depositSuccess = "minecraft:entity.experience_orb.pickup"
            # ATM 存入失败时
            depositFailed = "minecraft:entity.villager.no"
            # ATM 取出成功时
            withdrawSuccess = "minecraft:entity.item.pickup"
            # ATM 取出失败时
            withdrawFailed = "minecraft:entity.villager.no"
            # 上述所有音效的音量
            volume = 1.0
            # 上述所有音效的音调
            pitch = 1.0

            # 自动备份设置
            # 备份内容：每个玩家的虚拟币余额，文件名按前面指定的时区生成
            # 默认备份到：<世界目录>/data/shop/backup，可更改路径名，也可以填写为绝对路径
            [backup]
            # 每隔多少秒自动备份一次，设置为0可以关闭
            intervalSeconds = 300

            # 备份文件夹路径
            directory = "backup"

            # ATM 实体货币设置
            # 玩家可以通过/atm把指定物品存入为虚拟币，也可以把虚拟币取出为指定物品。
            [atm]
            # 作为实体货币的物品 ID。
            # 建议使用之前自定义NPC Mod中的货币
            item = "minecraft:gold_ingot"

            # 每个实体货币物品对应多少虚拟币，默认是1:1兑换，不能大于maxBalance。
            valuePerItem = "1.00"

            # 玩家死亡扣款设置
            # 扣款金额按死亡瞬间的余额计算，并向下取整到最小货币单位（0.01）。
            # 自动识别原版、常规模组弹射物/宠物主人，以及混合端插件提供的玩家击杀者。
            # 外部模组还可以通过 DeathPenaltyEvent 覆盖击杀归属；无玩家击杀者时直接扣除。
            # 击杀者余额达到 maxBalance 时，无法转入的部分也会被直接扣除。
            [deathPenalty]
            # 是否开启玩家死亡扣款，默认关闭。
            enabled = false

            # 扣除余额的比例，必须大于 0 且不能大于 1；0.10 表示 10%。
            ratio = 0.10
            """;

    private final Path path;
    private final AtomicReference<Snapshot> active = new AtomicReference<>();

    public EconomyConfig() {
        this(FMLPaths.CONFIGDIR.get().resolve("shop").resolve("economy.toml"));
    }

    EconomyConfig(Path path) {
        this.path = path;
    }

    public Snapshot loadInitial() throws IOException {
        createDefaultIfMissing();
        Snapshot loaded = parse();
        active.set(loaded);
        return loaded;
    }

    public Snapshot reload() throws IOException {
        Snapshot loaded = parse();
        active.set(loaded);
        return loaded;
    }

    public Snapshot get() {
        Snapshot snapshot = active.get();
        if (snapshot == null) {
            throw new IllegalStateException("经济配置尚未加载");
        }
        return snapshot;
    }

    public Path path() {
        return path;
    }

    private void createDefaultIfMissing() throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.writeString(path, DEFAULT_CONFIG, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    @SuppressWarnings("unchecked")
    private Snapshot parse() throws IOException {
        try (CommentedFileConfig file = CommentedFileConfig.builder(path).sync().build()) {
            file.load();
            Number version = require(file, "schemaVersion", Number.class);
            if (version.intValue() != 1) {
                throw new IllegalArgumentException("不支持的 schemaVersion: " + version);
            }
            long maxBalance = Money.parse(require(file, "maxBalance", String.class), false);
            ZoneId logTimeZone = parseZone(optional(file, "logTimeZone", String.class).orElse("Asia/Shanghai"));
            long backupIntervalSeconds = parseBackupInterval(optional(file, "backup.intervalSeconds", Number.class).orElse(300));
            String backupDirectory = parseBackupDirectory(optional(file, "backup.directory", String.class).orElse("backup"));
            DeathPenaltySettings deathPenalty = new DeathPenaltySettings(
                    optional(file, "deathPenalty.enabled", Boolean.class).orElse(false),
                    parseDeathPenaltyRatio(optional(file, "deathPenalty.ratio", Number.class).orElse(0.10)));
            SoundSettings sounds = new SoundSettings(
                    parseSound(optional(file, "sounds.sellSuccess", String.class).orElse("minecraft:entity.player.levelup"), "出售成功音效"),
                    parseSound(optional(file, "sounds.sellFailed", String.class).orElse("minecraft:entity.villager.no"), "出售失败音效"),
                    parseSound(optional(file, "sounds.depositSuccess", String.class).orElse("minecraft:entity.experience_orb.pickup"), "存入成功音效"),
                    parseSound(optional(file, "sounds.depositFailed", String.class).orElse("minecraft:entity.villager.no"), "存入失败音效"),
                    parseSound(optional(file, "sounds.withdrawSuccess", String.class).orElse("minecraft:entity.item.pickup"), "取出成功音效"),
                    parseSound(optional(file, "sounds.withdrawFailed", String.class).orElse("minecraft:entity.villager.no"), "取出失败音效"),
                    parseSoundNumber(optional(file, "sounds.volume", Number.class).orElse(1.0), "sounds.volume"),
                    parseSoundNumber(optional(file, "sounds.pitch", Number.class).orElse(1.0), "sounds.pitch"));
            ResourceLocation atmId = parseId(require(file, "atm.item", String.class), "ATM 物品");
            Item atmItem = requireRegisteredItem(atmId, "ATM 物品");
            long atmValue = Money.parse(require(file, "atm.valuePerItem", String.class), false);
            if (atmValue > maxBalance) {
                throw new IllegalArgumentException("ATM 单价不能大于最大余额");
            }
            try {
                if (Money.multiply(atmValue, 64) > maxBalance) {
                    throw new IllegalArgumentException("取出 64 个 ATM 物品的金额不能大于最大余额");
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("ATM 64 个取出档位金额溢出", ex);
            }

            Map<ResourceLocation, Long> prices = new LinkedHashMap<>();
            Object rawPrices = file.get("prices");
            if (rawPrices != null) {
                if (!(rawPrices instanceof List<?> entries)) {
                    throw new IllegalArgumentException("prices 必须是 TOML 表数组");
                }
                for (Object entry : entries) {
                    if (!(entry instanceof Config priceConfig)) {
                        throw new IllegalArgumentException("prices 中存在无效条目");
                    }
                    ResourceLocation id = parseId(require(priceConfig, "item", String.class), "价格物品");
                    // 插件 ID 存在物品的 PDC 中，并不是 Forge 注册物品。
                    Item pricedItem = id.getNamespace().equals("inoriloot") ? null : requireRegisteredItem(id, "价格物品");
                    long price = Money.parse(require(priceConfig, "price", String.class), false);
                    if (price > maxBalance) {
                        throw new IllegalArgumentException("物品单价不能大于最大余额: " + id);
                    }
                    try {
                        if (Money.multiply(price, pricedItem == null ? 64 : new ItemStack(pricedItem).getMaxStackSize()) > maxBalance) {
                            throw new IllegalArgumentException("单组物品价格不能大于最大余额: " + id);
                        }
                    } catch (ArithmeticException ex) {
                        throw new IllegalArgumentException("单组物品价格溢出: " + id, ex);
                    }
                    if (prices.putIfAbsent(id, price) != null) {
                        throw new IllegalArgumentException("重复的价格物品: " + id);
                    }
                }
            }
            Long atmShopPrice = prices.get(atmId);
            if (atmShopPrice != null && atmShopPrice > atmValue) {
                throw new IllegalArgumentException("ATM 物品的出售价格不能高于兑换价值，否则会产生无限套利: " + atmId);
            }
            return new Snapshot(maxBalance, logTimeZone, backupIntervalSeconds, backupDirectory, deathPenalty, sounds,
                    atmId, atmItem, atmValue, Collections.unmodifiableMap(prices));
        } catch (RuntimeException ex) {
            throw new IOException("配置校验失败: " + ex.getMessage(), ex);
        }
    }

    private static ResourceLocation parseId(String value, String label) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException(label + " ID 无效: " + value);
        }
        return id;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("logTimeZone 不是有效时区: " + value, ex);
        }
    }

    private static SoundEvent parseSound(String value, String label) {
        ResourceLocation id = parseId(value, label);
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (sound == null) {
            throw new IllegalArgumentException(label + "不存在: " + id);
        }
        return sound;
    }

    private static float parseSoundNumber(Number value, String key) {
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number <= 0.0 || number > 2.0) {
            throw new IllegalArgumentException(key + " 必须大于 0 且不能大于 2");
        }
        return (float) number;
    }

    private static long parseBackupInterval(Number value) {
        if ((value instanceof Double || value instanceof Float)
                && (!Double.isFinite(value.doubleValue()) || value.doubleValue() != Math.rint(value.doubleValue()))) {
            throw new IllegalArgumentException("backup.intervalSeconds 必须是非负整数秒");
        }
        long seconds = value.longValue();
        if (seconds < 0) {
            throw new IllegalArgumentException("backup.intervalSeconds 必须是非负整数秒");
        }
        return seconds;
    }

    private static BigDecimal parseDeathPenaltyRatio(Number value) {
        double approximate = value.doubleValue();
        if (!Double.isFinite(approximate)) {
            throw new IllegalArgumentException("deathPenalty.ratio 必须是有限数字");
        }
        final BigDecimal ratio;
        try {
            ratio = new BigDecimal(value.toString()).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("deathPenalty.ratio 不是有效比例: " + value, ex);
        }
        if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("deathPenalty.ratio 必须大于 0 且不能大于 1");
        }
        return ratio;
    }

    private static String parseBackupDirectory(String value) {
        String directory = value == null ? "" : value.trim();
        if (directory.isEmpty()) {
            throw new IllegalArgumentException("backup.directory 不能为空");
        }
        try {
            Path.of(directory);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("backup.directory 不是有效路径: " + value, ex);
        }
        return directory;
    }

    private static Item requireRegisteredItem(ResourceLocation id, String label) {
        if (!ForgeRegistries.ITEMS.containsKey(id)) {
            throw new IllegalArgumentException(label + "不存在: " + id);
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException(label + "不能是空气: " + id);
        }
        return item;
    }

    private static <T> T require(Config config, String key, Class<T> type) {
        Object value = config.get(key);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("缺少或类型错误的配置项 " + key);
        }
        return type.cast(value);
    }

    private static <T> java.util.Optional<T> optional(Config config, String key, Class<T> type) {
        Object value = config.get(key);
        if (value == null) return java.util.Optional.empty();
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("配置项类型错误 " + key);
        }
        return java.util.Optional.of(type.cast(value));
    }

    public record Snapshot(long maxBalance, ZoneId logTimeZone, long backupIntervalSeconds,
                           String backupDirectory, DeathPenaltySettings deathPenalty, SoundSettings sounds,
                           ResourceLocation atmItemId, Item atmItem, long atmValuePerItem,
                           Map<ResourceLocation, Long> prices) {
        public Snapshot {
            Objects.requireNonNull(logTimeZone);
            Objects.requireNonNull(backupDirectory);
            Objects.requireNonNull(deathPenalty);
            Objects.requireNonNull(sounds);
            Objects.requireNonNull(atmItemId);
            Objects.requireNonNull(atmItem);
            prices = Map.copyOf(prices);
        }

        public OptionalLong price(ItemStack stack) {
            if (stack.isEmpty() || InoriLootItems.isPlaceholder(stack)) return OptionalLong.empty();
            ResourceLocation lootId = InoriLootItems.id(stack);
            Long lootPrice = lootId == null ? null : prices.get(lootId);
            if (lootPrice != null) return OptionalLong.of(lootPrice);
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            Long value = id == null ? null : prices.get(id);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        }

        public boolean isAtmItem(ItemStack stack) {
            return !stack.isEmpty() && !InoriLootItems.isPlaceholder(stack) && stack.is(atmItem);
        }
    }

    public record DeathPenaltySettings(boolean enabled, BigDecimal ratio) {
        public DeathPenaltySettings {
            Objects.requireNonNull(ratio);
            if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("死亡扣款比例必须大于 0 且不能大于 1");
            }
        }

        public String percentageText() {
            return ratio.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
        }
    }

    public record SoundSettings(SoundEvent sellSuccess, SoundEvent sellFailed,
                                SoundEvent depositSuccess, SoundEvent depositFailed,
                                SoundEvent withdrawSuccess, SoundEvent withdrawFailed,
                                float volume, float pitch) {
        public SoundSettings {
            Objects.requireNonNull(sellSuccess);
            Objects.requireNonNull(sellFailed);
            Objects.requireNonNull(depositSuccess);
            Objects.requireNonNull(depositFailed);
            Objects.requireNonNull(withdrawSuccess);
            Objects.requireNonNull(withdrawFailed);
        }
    }
}
