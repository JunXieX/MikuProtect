package dev.junxiex.mikuprotect.command;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.util.TextUtil;
import dev.junxiex.mikuprotect.util.TimeUtil;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * 分页渲染查询结果。每条记录一行，行尾附带可点击的 /mp tp 建议。
 */
public final class LookupRenderer {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int SUBJECT_LIMIT = 28;

    private final MikuConfig config;

    public LookupRenderer(MikuConfig config) {
        this.config = config;
    }

    public void send(CommandSender sender, List<StoredActivity> activities, long total,
                     LookupQuery query, String sub, String rawArgs) {
        int limit = query.limit > 0 ? query.limit : config.pageSize;
        long pages = Math.max(1L, (total + limit - 1L) / limit);
        int page = Math.max(1, query.page);

        sendRaw(sender, "&8&m----------&r &bMikuProtect &7查询结果 &8&m----------");
        if (activities == null || activities.isEmpty()) {
            if (total <= 0L) {
                sendRaw(sender, TextUtil.PREFIX + "&7没有符合条件的记录，可尝试放宽 &ft:&7、&fr:&7 或 &fu:&7 条件。");
            } else {
                sendRaw(sender, TextUtil.PREFIX + "&e第 &f" + page + " &e页没有记录，共 &f" + pages + " &e页。");
                if (page > pages) {
                    sender.sendMessage(link("跳到最后一页", pageCommand(sub, rawArgs, pages)));
                }
            }
            return;
        }

        for (StoredActivity activity : activities) {
            if (sender instanceof org.bukkit.entity.Player) {
                sender.sendMessage(row(activity));
            } else {
                String text = format(activity);
                sender.sendMessage(TextUtil.color(text));
                Bukkit.getLogger().info(TextUtil.plain(text));
            }
        }

        sendRaw(sender, TextUtil.PREFIX + "&7第 &f" + page + "&7/&f" + pages
                + " &7页 &8| &7共 &f" + total + " &7条 &8| &7本页 &f" + activities.size() + " &7条");

        Component navigation = Component.text("页码: ", NamedTextColor.GRAY);
        if (page > 1) {
            navigation = navigation.append(link("« 上一页", pageCommand(sub, rawArgs, page - 1)));
        }
        navigation = navigation.append(Component.text(" " + page + "/" + pages + " ", NamedTextColor.WHITE));
        if (page < pages) {
            navigation = navigation.append(link("下一页 »", pageCommand(sub, rawArgs, page + 1)));
        }
        if (page > 1 || page < pages) {
            sender.sendMessage(navigation);
        }
    }

    /** 单条记录的单行文本表示，便于日志或其它子命令复用。 */
    public String format(StoredActivity activity) {
        StringBuilder builder = new StringBuilder(144);
        builder.append("&8[&7#").append(activity.id()).append("&8] &7").append(TimeUtil.format(activity.time()));
        builder.append(" &8| &e").append(value(activity.userName()));
        builder.append(" &8| &b").append(activity.action() == null ? "?" : activity.action().key());
        builder.append(" &8| &f").append(subject(activity));
        builder.append(" &8| &7").append(value(activity.worldName()));
        builder.append(" &f").append(activity.x()).append("&7,&f").append(activity.y())
                .append("&7,&f").append(activity.z());
        if (activity.containers() != null && !activity.containers().isEmpty()) {
            builder.append(" &8[&7容器x").append(activity.containers().size()).append("&8]");
        }
        if (activity.rolledBack()) {
            builder.append(" &c&l[已回滚]");
        }
        return builder.toString();
    }

    private Component row(StoredActivity activity) {
        Component line = LEGACY.deserialize(format(activity));
        if (activity.worldName() == null || activity.worldName().isBlank()) {
            return line;
        }
        String command = "/mp tp " + activity.worldName() + " " + activity.x() + " " + activity.y() + " " + activity.z();
        Component teleport = Component.text(" [传送]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(command));
        teleport = teleport.hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
        return line.append(teleport);
    }

    private static String subject(StoredActivity activity) {
        if (notBlank(activity.material())) {
            return shortKey(activity.material());
        }
        if (notBlank(activity.entityType())) {
            return shortKey(activity.entityType());
        }
        if (notBlank(activity.targetUser())) {
            return "-> " + activity.targetUser();
        }
        if (notBlank(activity.message())) {
            // Chat text is rendered through the legacy & serializer; a raw & would let a player
            // spoof the rolled-back marker or other status colors in the lookup line.
            String text = activity.message().replace('&', '\uFF06').replace('\n', ' ');
            return "\"" + TextUtil.truncate(text, SUBJECT_LIMIT) + "\"";
        }
        if (notBlank(activity.blockData())) {
            return TextUtil.truncate(activity.blockData(), SUBJECT_LIMIT);
        }
        return "-";
    }

    private static String value(String text) {
        return notBlank(text) ? text : "-";
    }

    private static String shortKey(String value) {
        int colon = value.indexOf(':');
        return colon >= 0 && colon + 1 < value.length() ? value.substring(colon + 1) : value;
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(TextUtil.color(message));
        if (!(sender instanceof org.bukkit.entity.Player)) {
            Bukkit.getLogger().info(TextUtil.plain(message));
        }
    }

    private static Component link(String label, String command) {
        Component component = Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(command));
        return component.hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    private static String pageCommand(String sub, String rawArgs, long page) {
        StringBuilder builder = new StringBuilder("/mp ");
        builder.append(sub == null || sub.isBlank() ? "lookup" : sub);
        for (String token : QueryParser.tokenize(rawArgs)) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("p:") || lower.startsWith("page:")) {
                continue;
            }
            builder.append(' ').append(quote(token));
        }
        return builder.append(" p:").append(page).toString();
    }

    private static String quote(String token) {
        if (token.indexOf(' ') < 0) {
            return token;
        }
        char quote = token.indexOf('"') >= 0 ? '\'' : '"';
        return quote + token + quote;
    }
}
