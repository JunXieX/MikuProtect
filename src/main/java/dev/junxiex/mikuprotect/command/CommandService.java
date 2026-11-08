package dev.junxiex.mikuprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.Permissions;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.RollbackRequest;
import dev.junxiex.mikuprotect.model.RollbackResult;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.util.TextUtil;
import dev.junxiex.mikuprotect.util.TimeUtil;
import java.time.Duration;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /mp 命令入口。命令线程可能是区域线程或全局线程，因此查询、回滚、清理全部交给异步服务，
 * 命令层只做参数解析、权限判定、行数上限检查和结果播报。
 */
public final class CommandService {

    private static final long DAY_MILLIS = 86_400_000L;

    private final MikuProtectPlugin plugin;

    public CommandService(MikuProtectPlugin plugin) {
        this.plugin = plugin;
    }

    /** 在 LifecycleEvents.COMMANDS 上注册 /mp 与别名 /mikuprotect。 */
    public void registerCommands() {
        LiteralCommandNode<CommandSourceStack> root = Commands.literal("mp")
                .requires(source -> source.getSender().hasPermission(Permissions.COMMAND))
                .executes(context -> {
                    help(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(branch("help", Permissions.COMMAND))
                .then(branch("lookup", Permissions.LOOKUP))
                .then(branch("near", Permissions.LOOKUP))
                .then(branch("rollback", Permissions.ROLLBACK))
                .then(branch("restore", Permissions.RESTORE))
                .then(branch("preview", Permissions.PREVIEW))
                .then(branch("apply", Permissions.PREVIEW))
                .then(branch("cancel", Permissions.PREVIEW))
                .then(branch("undo", Permissions.UNDO))
                .then(branch("purge", Permissions.PURGE))
                .then(branch("status", Permissions.STATUS))
                .then(branch("flush", Permissions.STATUS))
                .then(branch("inspect", Permissions.INSPECT))
                .then(branch("reload", Permissions.RELOAD))
                .then(branch("teleport", Permissions.TELEPORT))
                .then(branch("tp", Permissions.TELEPORT))
                .build();
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(root, "MikuProtect 主命令", List.of("mikuprotect")));
    }

    /** 供 Brigadier 节点与其它入口共用的分发方法。 */
    public void execute(CommandSender sender, String sub, String args) {
        if (sender == null) {
            return;
        }
        String key = sub == null ? "" : sub.trim().toLowerCase(Locale.ROOT);
        String raw = args == null ? "" : args.trim();
        switch (key) {
            case "", "help" -> help(sender);
            case "lookup" -> lookup(sender, raw, false);
            case "near" -> lookup(sender, raw, true);
            case "rollback" -> modify(sender, raw, RollbackRequest.Mode.ROLLBACK);
            case "restore" -> modify(sender, raw, RollbackRequest.Mode.RESTORE);
            case "preview" -> modify(sender, raw, RollbackRequest.Mode.PREVIEW);
            case "apply" -> applyPreview(sender);
            case "cancel" -> cancelPreview(sender);
            case "undo" -> undo(sender);
            case "purge" -> purge(sender, raw);
            case "status" -> status(sender);
            case "flush" -> flush(sender);
            case "inspect" -> inspect(sender);
            case "reload" -> reload(sender);
            case "teleport", "tp" -> teleport(sender, raw);
            default -> send(sender, "&c未知子命令: " + sub + "，使用 &f/mp help &c查看帮助。");
        }
    }

    /** 输出命令帮助。 */
    public void help(CommandSender sender) {
        if (!require(sender, Permissions.COMMAND)) {
            return;
        }
        sendRaw(sender, "&8&m----------&r &bMikuProtect &7命令帮助 &8&m----------");
        sendRaw(sender, "&b/mp lookup [条件] &8- &7分页查询记录");
        sendRaw(sender, "&b/mp near [条件] &8- &7查询当前位置附近的记录");
        sendRaw(sender, "&b/mp rollback [条件] &8- &7回滚匹配的记录");
        sendRaw(sender, "&b/mp restore [条件] &8- &7恢复匹配的记录");
        sendRaw(sender, "&b/mp preview [条件] &8- &7预览回滚，不写入世界");
        sendRaw(sender, "&b/mp apply &8- &7应用当前预览");
        sendRaw(sender, "&b/mp cancel &8- &7取消当前预览");
        sendRaw(sender, "&b/mp undo &8- &7撤销上一次回滚或恢复");
        sendRaw(sender, "&b/mp purge [t:30d] [w:世界] [u:玩家] &8- &7清理旧记录");
        sendRaw(sender, "&b/mp status &8- &7查看存储与配置状态");
        sendRaw(sender, "&b/mp flush &8- &7把队列中的记录立即刷入存储");
        sendRaw(sender, "&b/mp inspect &8- &7开关检查器，右键方块查看最近记录");
        sendRaw(sender, "&b/mp reload &8- &7重载配置文件");
        sendRaw(sender, "&b/mp teleport &c<世界> <x> <y> <z> &8- &7传送到记录坐标上方");
        sendRaw(sender, "&7条件: &fu:玩家 &fa:动作 &fb:材质 &fe:实体 &fr:半径 &ft:时间 &fp:页码 &fl:每页数量");
        sendRaw(sender, "&7　　　&fw:世界 / #世界 &fmsg:文本 &fsort:asc|desc &fa!:排除动作");
        sendRaw(sender, "&7示例: &f/mp lookup u:Steve a:block-break t:7d p:1");
        sendRaw(sender, "&7　　　&f/mp near r:20 t:1h &8| &f/mp rollback u:Steve t:30m");
    }

    private LiteralArgumentBuilder<CommandSourceStack> branch(String name, String permission) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission))
                .executes(context -> {
                    execute(context.getSource().getSender(), name, "");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> {
                            execute(context.getSource().getSender(), name, StringArgumentType.getString(context, "args"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void lookup(CommandSender sender, String args, boolean near) {
        if (!require(sender, Permissions.LOOKUP) || !serviceReady(sender)) {
            return;
        }
        MikuConfig config = config(sender);
        if (config == null) {
            return;
        }
        Location center = sender instanceof Player player ? player.getLocation() : null;
        QueryParser.Result parsed;
        try {
            parsed = new QueryParser(config).parse(args, center, near);
        } catch (RuntimeException exception) {
            send(sender, "&c查询参数解析失败: " + message(exception));
            return;
        }
        if (!parsed.ok()) {
            send(sender, "&c" + parsed.error());
            return;
        }
        reportWarnings(sender, parsed.warnings());
        LookupQuery query = parsed.query();
        String sub = near ? "near" : "lookup";
        LookupRenderer renderer = new LookupRenderer(config);
        try {
            plugin.lookup().lookup(query)
                    .thenCombine(plugin.lookup().count(query), LookupPage::new)
                    .whenComplete((page, throwable) -> Scheduler.global(() -> {
                        if (throwable != null) {
                            send(sender, "&c查询失败: " + message(throwable));
                            return;
                        }
                        renderer.send(sender, page.rows(), page.total(), query, sub, args);
                    }));
        } catch (RuntimeException exception) {
            send(sender, "&c查询调度失败: " + message(exception));
        }
    }

    private void modify(CommandSender sender, String args, RollbackRequest.Mode mode) {
        String permission = switch (mode) {
            case ROLLBACK -> Permissions.ROLLBACK;
            case RESTORE -> Permissions.RESTORE;
            case PREVIEW -> Permissions.PREVIEW;
        };
        if (!require(sender, permission) || !serviceReady(sender)) {
            return;
        }
        MikuConfig config = config(sender);
        if (config == null) {
            return;
        }
        Location center = sender instanceof Player player ? player.getLocation() : null;
        QueryParser.Result parsed = new QueryParser(config).parse(args, center, false);
        if (!parsed.ok()) {
            send(sender, "&c" + parsed.error());
            return;
        }
        if (parsed.filters() == 0) {
            send(sender, "&c请至少指定一个条件（如 &fu:玩家&c、&fa:动作&c、&fr:半径&c、&ft:时间&c），避免误操作大范围数据。");
            return;
        }
        reportWarnings(sender, parsed.warnings());
        LookupQuery query = parsed.query();
        int maxRows = Math.max(1, config.rollbackMaxRows);
        String actionLabel = label(mode);

        plugin.lookup().count(query).whenComplete((count, throwable) -> {
            if (throwable != null) {
                send(sender, "&c统计匹配记录失败: " + message(throwable));
                return;
            }
            long total = count == null ? 0L : count;
            if (total <= 0L) {
                send(sender, "&7没有符合条件的记录。");
                return;
            }
            if (total > maxRows) {
                send(sender, "&c匹配 &f" + total + " &c条，超过单次上限 &f" + maxRows
                        + " &c条，请缩小范围（u:/t:/r:/a:）后重试。");
                return;
            }
            query.page(1);
            query.limit(maxRows);
            RollbackRequest request = new RollbackRequest(mode, query, sender, maxRows, config.applyPhysics);
            try {
                plugin.rollback().apply(request).whenComplete((result, error) -> {
                    if (error != null) {
                        String reason = message(error);
                        if (reason.contains("已有回滚/还原任务在运行")) {
                            send(sender, "&e已有回滚或还原任务正在运行，请稍后再试。");
                        } else {
                            send(sender, "&c" + actionLabel + "失败: " + reason);
                        }
                        return;
                    }
                    if (mode == RollbackRequest.Mode.PREVIEW) {
                        reportPreview(sender, result);
                    } else {
                        reportApplied(sender, actionLabel, result);
                    }
                });
            } catch (RuntimeException exception) {
                send(sender, "&c" + actionLabel + "失败: " + message(exception));
            }
        });
    }

    private void applyPreview(CommandSender sender) {
        if (!require(sender, Permissions.PREVIEW) || !serviceReady(sender)) {
            return;
        }
        try {
            plugin.rollback().applyPreview(sender).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    String reason = message(throwable);
                    if (reason.contains("没有待确认的预览")) {
                        send(sender, "&7当前没有待应用的预览，请先执行 &f/mp preview&7。");
                    } else {
                        send(sender, "&c应用预览失败: " + reason);
                    }
                    return;
                }
                reportApplied(sender, "应用预览", result);
            });
        } catch (RuntimeException exception) {
            send(sender, "&c应用预览失败: " + message(exception));
        }
    }

    private void cancelPreview(CommandSender sender) {
        if (!require(sender, Permissions.PREVIEW) || !serviceReady(sender)) {
            return;
        }
        try {
            if (plugin.rollback().cancelPreview(sender)) {
                send(sender, "&7已取消当前预览。");
            } else {
                send(sender, "&7没有待取消的预览。");
            }
        } catch (RuntimeException exception) {
            send(sender, "&c取消预览失败: " + message(exception));
        }
    }

    private void undo(CommandSender sender) {
        if (!require(sender, Permissions.UNDO) || !serviceReady(sender)) {
            return;
        }
        try {
            plugin.rollback().applyUndo(sender).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    String reason = message(throwable);
                    if (reason.contains("没有可撤销的操作")) {
                        send(sender, "&7没有可撤销的操作。");
                    } else {
                        send(sender, "&c撤销失败: " + reason);
                    }
                    return;
                }
                if (result == null || result.total() == 0) {
                    send(sender, "&7没有可撤销的操作。");
                    return;
                }
                reportApplied(sender, "撤销", result);
            });
        } catch (RuntimeException exception) {
            send(sender, "&c撤销失败: " + message(exception));
        }
    }

    private void purge(CommandSender sender, String args) {
        if (!require(sender, Permissions.PURGE)) {
            return;
        }
        MikuConfig config = config(sender);
        if (config == null) {
            return;
        }
        if (plugin.purge() == null || plugin.lookup() == null) {
            send(sender, "&c插件服务尚未就绪，请稍后再试。");
            return;
        }
        long now = System.currentTimeMillis();
        long before = now - Math.max(1, config.purgeDays) * DAY_MILLIS;
        String world = null;
        String user = null;

        for (String token : QueryParser.tokenize(args)) {
            if (token.startsWith("#")) {
                world = token.substring(1).trim();
                if (world.isEmpty()) {
                    send(sender, "&c世界名不可为空，例如 #world_nether");
                    return;
                }
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                send(sender, "&c未识别的参数: " + token + "（purge 支持 t:/d:/w:/u:）");
                return;
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1).trim();
            if (value.isEmpty()) {
                send(sender, "&c参数 " + key + ": 缺少值");
                return;
            }
            switch (key) {
                case "t", "time", "before" -> {
                    try {
                        before = now - TimeUtil.parseDuration(value);
                    } catch (RuntimeException exception) {
                        send(sender, "&c时间格式无效: " + value + "（示例 30d、12h）");
                        return;
                    }
                }
                case "d", "days" -> {
                    int days;
                    try {
                        days = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        send(sender, "&c天数必须是整数: " + value);
                        return;
                    }
                    if (days < 0) {
                        send(sender, "&c天数必须大于或等于 0: " + value);
                        return;
                    }
                    before = now - days * DAY_MILLIS;
                }
                case "w", "world" -> world = value;
                case "u", "user", "player" -> user = value;
                default -> {
                    send(sender, "&cpurge 只支持 t:时间、d:天数、w:世界、u:玩家");
                    return;
                }
            }
        }

        long cutoff = before;
        String targetWorld = world;
        String targetUser = user;
        LookupQuery countQuery = LookupQuery.all().before(cutoff);
        if (targetWorld != null) {
            countQuery.world(targetWorld);
        }
        if (targetUser != null) {
            countQuery.user(targetUser);
        }

        if (plugin.purge().running()) {
            send(sender, "&e已有清理任务正在运行，请稍后再试。");
            return;
        }
        plugin.lookup().count(countQuery).whenComplete((count, throwable) -> {
            if (throwable != null) {
                send(sender, "&c统计待清理记录失败: " + message(throwable));
                return;
            }
            long total = count == null ? 0L : count;
            if (total <= 0L) {
                send(sender, "&7没有符合条件的记录。");
                return;
            }
            send(sender, "&7正在清理 &f" + total + " &7条早于 &f" + TimeUtil.format(cutoff) + " &7的记录"
                    + (targetWorld == null ? "" : "（世界 " + targetWorld + "）")
                    + (targetUser == null ? "" : "（玩家 " + targetUser + "）") + "…");
            plugin.purge().purge(cutoff, targetWorld, targetUser).whenComplete((removed, error) -> {
                if (error != null) {
                    if (unwrap(error) instanceof IllegalStateException) {
                        send(sender, "&e已有清理任务正在运行，请稍后再试。");
                    } else {
                        send(sender, "&c清理失败: " + message(error));
                    }
                    return;
                }
                send(sender, "&a清理完成：删除 &f" + (removed == null ? 0 : removed) + " &a条记录。");
            });
        });
    }

    private void inspect(CommandSender sender) {
        if (!require(sender, Permissions.INSPECT)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "&c检查器只能由玩家使用。");
            return;
        }
        if (plugin.inspection() == null) {
            send(sender, "&c检查器尚未就绪。");
            return;
        }
        boolean enabled = plugin.inspection().toggle(player);
        send(sender, enabled ? "&a检查器已开启，右键方块查看记录。" : "&7检查器已关闭。");
    }

    private void flush(CommandSender sender) {
        if (!require(sender, Permissions.STATUS) || !serviceReady(sender)) {
            return;
        }
        send(sender, "&7正在刷写队列...");
        Scheduler.async(() -> {
            plugin.recording().flush(Duration.ofSeconds(10));
            Scheduler.global(() -> send(sender, "&a刷写完成，队列剩余 &f" + plugin.recording().size() + " &a条。"));
        });
    }

    private void status(CommandSender sender) {
        if (!require(sender, Permissions.STATUS)) {
            return;
        }
        MikuConfig config = config(sender);
        if (config == null) {
            return;
        }
        Storage storage = plugin.storage();
        boolean healthy = false;
        if (storage != null) {
            try {
                healthy = storage.healthy();
            } catch (RuntimeException exception) {
                healthy = false;
            }
        }
        sendRaw(sender, "&8&m----------&r &bMikuProtect &7状态 &8&m----------");
        long records = -1L;
        if (storage != null && healthy) {
            try {
                records = storage.count(LookupQuery.all());
            } catch (Exception exception) {
                healthy = false;
            }
        }
        sendRaw(sender, "&7存储: &f" + (storage == null ? "未就绪" : storage.type().name().toLowerCase(Locale.ROOT))
                + " &7| &f" + (healthy ? "正常" : "异常")
                + (records >= 0 ? " &7| 记录 &f" + records : ""));
        int enabledActions = ActionType.values().length - config.disabledActions.size();
        sendRaw(sender, "&7记录动作: &f" + enabledActions + " &7个可用，已禁用 &f" + config.disabledActions.size() + " &7个");
        sendRaw(sender, "&7查询: &f默认半径 " + config.defaultRadius + " &7| &f最大半径 " + config.maxRadius
                + " &7| &f默认时间 " + durationText(config.defaultSince) + " &7| &f每页 " + config.pageSize);
        sendRaw(sender, "&7回滚: &f最大行数 " + config.rollbackMaxRows + " &7| &f预算 " + config.rollbackBudgetMs
                + "ms &7| &f物理 " + onOff(config.applyPhysics));
        sendRaw(sender, "&7清理: &f自动 " + onOff(config.purgeAuto) + " &7| &f保留 " + config.purgeDays
                + " 天 &7| &f运行中 " + (plugin.purge() != null && plugin.purge().running() ? "是" : "否"));
        sendRaw(sender, "&7调试: &f" + onOff(config.debug));
        RecordingService recording = plugin.recording();
        if (recording != null) {
            sendRaw(sender, "&7队列: &f" + recording.size() + "&7/&f" + recording.capacity()
                    + " &7| 已写 &f" + recording.written() + " &7| 丢弃 &f" + recording.dropped());
        }
    }

    private void reload(CommandSender sender) {
        if (!require(sender, Permissions.RELOAD)) {
            return;
        }
        try {
            plugin.reload();
            MikuConfig config = plugin.config();
            send(sender, "&a配置已重载。");
            if (config != null) {
                sendRaw(sender, "&7存储: &f" + config.storageType.name().toLowerCase(Locale.ROOT)
                        + " &7| &f每页 " + config.pageSize + " &7| &f最大半径 " + config.maxRadius
                        + " &7| &f回滚上限 " + config.rollbackMaxRows);
            }
        } catch (IllegalStateException exception) {
            send(sender, "&c重载失败: " + message(exception));
        } catch (RuntimeException exception) {
            send(sender, "&c重载失败: " + message(exception));
        }
    }

    private void teleport(CommandSender sender, String args) {
        if (!require(sender, Permissions.TELEPORT)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "&c该子命令只能由玩家执行。");
            return;
        }
        List<String> tokens = QueryParser.tokenize(args);
        World world;
        int index;
        if (tokens.size() == 4) {
            world = Bukkit.getWorld(tokens.get(0));
            if (world == null) {
                send(sender, "&c世界不存在: " + tokens.get(0));
                return;
            }
            index = 1;
        } else if (tokens.size() == 3) {
            world = player.getWorld();
            index = 0;
        } else {
            send(sender, "&c用法: /mp tp <世界> <x> <y> <z>");
            return;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(tokens.get(index));
            y = Double.parseDouble(tokens.get(index + 1));
            z = Double.parseDouble(tokens.get(index + 2));
        } catch (NumberFormatException exception) {
            send(sender, "&c坐标必须是数字。");
            return;
        }
        Location target = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleportAsync(target).whenComplete((success, throwable) -> {
            if (throwable != null) {
                send(sender, "&c传送失败: " + message(throwable));
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                send(sender, "&a已传送到 &f" + world.getName() + " " + format(x) + ", " + format(y) + ", " + format(z));
            } else {
                send(sender, "&c传送未完成，目标区块可能未加载。");
            }
        });
    }

    private void reportApplied(CommandSender sender, String label, RollbackResult result) {
        if (result == null) {
            send(sender, "&7" + label + "未返回结果。");
            return;
        }
        if (result.total() == 0) {
            send(sender, "&7没有需要处理的记录。");
            return;
        }
        String color = result.failed > 0 ? "&e" : "&a";
        send(sender, color + label + "完成：应用 &f" + result.applied + " &7处，跳过 &f" + result.skipped
                + " &7处，失败 &f" + result.failed + " &7处。");
        if (!result.completed) {
            send(sender, "&e部分记录未完成，可能受区块加载或时间预算限制。");
        }
        reportErrors(sender, result);
    }

    private void reportPreview(CommandSender sender, RollbackResult result) {
        if (result == null) {
            send(sender, "&7预览未返回结果。");
            return;
        }
        if (result.total() == 0) {
            send(sender, "&7没有可预览的变更。");
            return;
        }
        send(sender, "&a预览完成：共 &f" + result.total() + " &a处，可应用 &f" + result.applied
                + " &a处，跳过 &f" + result.skipped + " &a处，失败 &f" + result.failed + " &a处。");
        if (!result.completed) {
            send(sender, "&e部分记录未能规划，可能受区块加载限制。");
        }
        reportErrors(sender, result);
        send(sender, "&7执行 &f/mp apply &7应用预览，或 &f/mp cancel &7取消。");
    }

    private void reportErrors(CommandSender sender, RollbackResult result) {
        int shown = 0;
        for (String error : result.errors) {
            if (shown >= 3) {
                send(sender, "&7... 另有 &f" + (result.errors.size() - 3) + " &7条错误未显示。");
                return;
            }
            send(sender, "&c- " + TextUtil.truncate(error, 120));
            shown++;
        }
    }

    private void reportWarnings(CommandSender sender, List<String> warnings) {
        for (String warning : warnings) {
            send(sender, "&e" + warning);
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "&c你没有权限: &f" + permission);
        return false;
    }

    private boolean serviceReady(CommandSender sender) {
        if (plugin.lookup() == null || plugin.rollback() == null) {
            send(sender, "&c插件服务尚未就绪，请稍后再试。");
            return false;
        }
        return true;
    }

    private MikuConfig config(CommandSender sender) {
        MikuConfig config = plugin.config();
        if (config == null) {
            send(sender, "&c插件配置尚未就绪，请稍后再试。");
        }
        return config;
    }

    private void send(CommandSender sender, String message) {
        String text = TextUtil.PREFIX + message;
        deliver(sender, TextUtil.color(text));
    }

    private void sendRaw(CommandSender sender, String message) {
        deliver(sender, TextUtil.color(message));
    }

    /**
     * Vanilla console/RCON senders are not safe to message from arbitrary async threads; hop to the
     * global region when the callback is off the server thread.
     */
    private void deliver(CommandSender sender, String colored) {
        Runnable task = () -> {
            sender.sendMessage(colored);
            if (!(sender instanceof Player)) {
                plugin.getLogger().info(TextUtil.plain(colored));
            }
        };
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Scheduler.global(task);
        }
    }

    private static String label(RollbackRequest.Mode mode) {
        return switch (mode) {
            case ROLLBACK -> "回滚";
            case RESTORE -> "恢复";
            case PREVIEW -> "预览";
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String message(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    private static String durationText(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long seconds = millis / 1000L;
        if (seconds % 86_400L == 0L) {
            return (seconds / 86_400L) + "d";
        }
        if (seconds % 3_600L == 0L) {
            return (seconds / 3_600L) + "h";
        }
        if (seconds % 60L == 0L) {
            return (seconds / 60L) + "m";
        }
        return seconds + "s";
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private record LookupPage(List<StoredActivity> rows, long total) {
    }
}
