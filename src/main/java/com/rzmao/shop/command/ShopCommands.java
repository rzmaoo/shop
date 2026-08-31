package com.rzmao.shop.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.authlib.GameProfile;
import com.rzmao.shop.EconomyService;
import com.rzmao.shop.Shop;
import com.rzmao.shop.money.Money;
import com.rzmao.shop.permission.ShopPermissions;
import com.rzmao.shop.storage.AuditContext;
import com.rzmao.shop.storage.EconomyDatabase;
import com.rzmao.shop.text.ShopText;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ShopCommands {
    private static final int PAGE_SIZE = 10;

    private ShopCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
                .executes(ShopCommands::openShop)
                .then(Commands.literal("reload")
                        .requires(source -> allowed(source, ShopPermissions.RELOAD, 3))
                        .executes(ShopCommands::reload))
                .then(Commands.literal("balance")
                        .requires(source -> allowed(source, ShopPermissions.BALANCE, 3))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ShopCommands::balance)))
                .then(Commands.literal("logs")
                        .requires(source -> allowed(source, ShopPermissions.LOGS, 3))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(context -> logs(context, 1, null, null))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000))
                                        .executes(context -> logs(context, IntegerArgumentType.getInteger(context, "page"), null, null)))
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .executes(context -> filteredLogs(context, 1))
                                                .then(Commands.argument("filteredPage", IntegerArgumentType.integer(1, 100_000))
                                                        .executes(context -> filteredLogs(context, IntegerArgumentType.getInteger(context, "filteredPage")))))))));

        dispatcher.register(Commands.literal("atm")
                .requires(source -> allowed(source, ShopPermissions.USE_ATM, 0))
                .executes(ShopCommands::openAtm));
    }

    private static int openShop(CommandContext<CommandSourceStack> context) {
        if (!allowed(context.getSource(), ShopPermissions.USE_SHOP, 0)) {
            reject(context.getSource(), ShopText.get("shop.command.no_permission"));
            return 0;
        }
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception ex) {
            context.getSource().sendFailure(ShopText.text("shop.command.player_only"));
            return 0;
        }
        try {
            requireService(context.getSource()).openShop(player);
            return 1;
        } catch (Exception ex) {
            reject(context.getSource(), ShopText.get("shop.command.open_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int openAtm(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception ex) {
            context.getSource().sendFailure(ShopText.text("shop.command.player_only"));
            return 0;
        }
        try {
            requireService(context.getSource()).openAtm(player);
            return 1;
        } catch (Exception ex) {
            reject(context.getSource(), ShopText.get("shop.command.atm_open_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            boolean wasDisabled = Shop.service().isEmpty();
            EconomyService service = wasDisabled ? Shop.startService(context.getSource().getServer()) : requireService(context.getSource());
            var snapshot = wasDisabled ? service.config() : service.reload();
            try {
                service.database().audit(null, "CONFIG_RELOAD", "SUCCESS", null,
                        "价格条目=" + snapshot.prices().size() + ", ATM=" + snapshot.atmItemId()
                                + ", 死亡扣款=" + snapshot.deathPenalty().enabled()
                                + ", 比例=" + snapshot.deathPenalty().percentageText(), AuditContext.source(context.getSource()));
            } catch (Exception auditFailure) {
                context.getSource().sendFailure(ShopText.text("shop.command.audit_failed", auditFailure.getMessage()));
            }
            context.getSource().sendSuccess(() -> ShopText.text("shop.command.reload_success", snapshot.prices().size(),
                    snapshot.atmItemId(), Money.format(snapshot.atmValuePerItem()), snapshot.logTimeZone(),
                    snapshot.deathPenalty().enabled() ? "开启" : "关闭", snapshot.deathPenalty().percentageText())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception ex) {
            Shop.service().ifPresent(service -> {
                try {
                    service.database().audit(null, "CONFIG_RELOAD", "REJECTED", ex.getMessage(),
                            "保留旧配置", AuditContext.source(context.getSource()));
                } catch (Exception ignored) { }
            });
            reject(context.getSource(), ShopText.get("shop.command.reload_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int balance(CommandContext<CommandSourceStack> context) {
        try {
            EconomyService service = requireService(context.getSource());
            EconomyDatabase.PlayerIdentity target = resolve(context.getSource(), service, StringArgumentType.getString(context, "target"));
            int escrow = service.database().escrowItemCount(target.uuid());
            service.database().audit(null, "ADMIN_BALANCE_QUERY", "SUCCESS", null,
                    "target=" + target.uuid() + "，余额=" + Money.format(target.balance())
                            + "，保留物品=" + escrow + " 个", AuditContext.source(context.getSource()));
            context.getSource().sendSuccess(() -> ShopText.text("shop.command.balance", target.name(), target.uuid(),
                    Money.format(target.balance()), escrow).withStyle(ChatFormatting.GOLD), false);
            return 1;
        } catch (Exception ex) {
            reject(context.getSource(), ShopText.get("shop.command.balance_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int logs(CommandContext<CommandSourceStack> context, int page, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            reject(context.getSource(), ShopText.get("shop.command.time_order"));
            return 0;
        }
        try {
            EconomyService service = requireService(context.getSource());
            ZoneId logTimeZone = service.config().logTimeZone();
            EconomyDatabase.PlayerIdentity target = resolve(context.getSource(), service, StringArgumentType.getString(context, "target"));
            List<EconomyDatabase.AuditRow> fetched = service.database().queryLogs(target.uuid(), from, to, page, PAGE_SIZE);
            boolean hasNext = fetched.size() > PAGE_SIZE;
            List<EconomyDatabase.AuditRow> rows = fetched.subList(0, Math.min(PAGE_SIZE, fetched.size()));
            service.database().audit(null, "ADMIN_LOG_QUERY", "SUCCESS", null,
                    "target=" + target.uuid() + "，页=" + page + ", from=" + from + ", to=" + to,
                    AuditContext.source(context.getSource()));
            context.getSource().sendSuccess(() -> ShopText.text("shop.logs.title", target.name(), page)
                    .withStyle(ChatFormatting.GOLD), false);
            for (EconomyDatabase.AuditRow row : rows) {
                context.getSource().sendSuccess(() -> renderRow(row, logTimeZone), false);
            }
            if (rows.isEmpty()) context.getSource().sendSuccess(() -> ShopText.text("shop.logs.empty"), false);
            context.getSource().sendSuccess(() -> navigation(target, page, hasNext, from, to, logTimeZone), false);
            return rows.size();
        } catch (Exception ex) {
            reject(context.getSource(), ShopText.get("shop.command.logs_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int filteredLogs(CommandContext<CommandSourceStack> context, int page) {
        try {
            ZoneId logTimeZone = requireService(context.getSource()).config().logTimeZone();
            return logs(context, page, parseTime(context, "from", logTimeZone), parseTime(context, "to", logTimeZone));
        } catch (IllegalArgumentException ex) {
            reject(context.getSource(), ex.getMessage());
            return 0;
        } catch (Exception ex) {
            reject(context.getSource(), ShopText.get("shop.command.logs_failed", ex.getMessage()));
            return 0;
        }
    }

    private static Component renderRow(EconomyDatabase.AuditRow row, ZoneId logTimeZone) {
        String delta = row.delta() == null ? "" : ShopText.get("shop.logs.amount_change", Money.format(row.delta()));
        String summary = ShopText.get("shop.logs.line", row.id(), LogTime.DISPLAY_FORMAT.withZone(logTimeZone).format(row.time()),
                ShopText.auditAction(row.action()), ShopText.auditOutcome(row.outcome()), delta);
        StringBuilder hover = new StringBuilder();
        hover.append(ShopText.get("shop.logs.operation_id", row.operationId())).append('\n')
                .append(ShopText.get("shop.logs.actor", row.actorName())).append('\n');
        if (row.reason() != null) hover.append(ShopText.get("shop.logs.reason", row.reason())).append('\n');
        if (row.balanceBefore() != null) hover.append(ShopText.get("shop.logs.balance", Money.format(row.balanceBefore()),
                Money.format(row.balanceAfter()))).append('\n');
        if (row.itemSummary() != null) hover.append(ShopText.get("shop.logs.items", row.itemSummary())).append('\n');
        if (row.itemHash() != null) hover.append(ShopText.get("shop.logs.item_hash", row.itemHash())).append('\n');
        if (row.dimension() != null) hover.append(ShopText.get("shop.logs.location", ShopText.dimension(row.dimension()),
                row.x(), row.y(), row.z())).append('\n');
        if (row.details() != null) hover.append(ShopText.get("shop.logs.details", row.details()));
        String hoverText = hover.length() > 1800 ? hover.substring(0, 1800) + "…" : hover.toString();
        return Component.literal(summary).withStyle(style -> style
                .withColor("SUCCESS".equals(row.outcome()) ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText))));
    }

    private static Component navigation(EconomyDatabase.PlayerIdentity target, int page, boolean hasNext,
                                        Instant from, Instant to, ZoneId logTimeZone) {
        Component result = ShopText.text("shop.logs.navigation");
        if (page > 1) result = result.copy().append(pageLink(ShopText.get("shop.logs.previous"),
                command(target, page - 1, from, to, logTimeZone)));
        if (hasNext) result = result.copy().append(pageLink(ShopText.get("shop.logs.next"),
                command(target, page + 1, from, to, logTimeZone)));
        return result;
    }

    private static Component pageLink(String label, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static String command(EconomyDatabase.PlayerIdentity target, int page, Instant from, Instant to, ZoneId logTimeZone) {
        if (from == null || to == null) return "/shop logs " + target.uuid() + " " + page;
        return "/shop logs " + target.uuid() + " " + LogTime.formatForCommand(from, logTimeZone)
                + " " + LogTime.formatForCommand(to, logTimeZone) + " " + page;
    }

    private static Instant parseTime(CommandContext<CommandSourceStack> context, String name, ZoneId logTimeZone) {
        String raw = StringArgumentType.getString(context, name);
        try {
            return LogTime.parseBound(raw, logTimeZone);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(ShopText.get("shop.command.time_format"));
        }
    }

    private static EconomyDatabase.PlayerIdentity resolve(CommandSourceStack source, EconomyService service, String input) throws Exception {
        List<EconomyDatabase.PlayerIdentity> found = new ArrayList<>(service.database().findPlayers(input));
        GameProfile cached = null;
        try {
            UUID uuid = UUID.fromString(input);
            cached = source.getServer().getProfileCache().get(uuid).orElse(null);
        } catch (IllegalArgumentException ignored) {
            cached = source.getServer().getProfileCache().get(input).orElse(null);
        }
        UUID cachedId = cached == null ? null : cached.getId();
        if (cachedId != null && found.stream().noneMatch(player -> player.uuid().equals(cachedId))) {
            found.add(service.database().ensurePlayer(cachedId, cached.getName()));
        }
        if (found.isEmpty()) throw new IllegalArgumentException(ShopText.get("shop.command.player_missing"));
        if (found.size() > 1) throw new IllegalArgumentException(ShopText.get("shop.command.player_ambiguous"));
        return found.get(0);
    }

    private static EconomyService requireService(CommandSourceStack source) {
        return Shop.service().orElseThrow(() -> new IllegalStateException(ShopText.get("shop.command.service_disabled")));
    }

    private static boolean allowed(CommandSourceStack source,
                                   net.minecraftforge.server.permission.nodes.PermissionNode<Boolean> node,
                                   int consoleLevel) {
        if (source.getEntity() instanceof ServerPlayer player) return PermissionAPI.getPermission(player, node);
        return source.hasPermission(consoleLevel);
    }

    private static void reject(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
