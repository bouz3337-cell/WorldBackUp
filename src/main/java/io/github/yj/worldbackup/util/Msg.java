package io.github.yj.worldbackup.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** MiniMessage 기반 메시지 유틸. */
public final class Msg {

    public static final String PREFIX =
            "<dark_gray>[</dark_gray><gradient:#5eead4:#38bdf8><bold>WorldBackUp</bold></gradient><dark_gray>]</dark_gray> ";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public static Component prefixed(String miniMessage) {
        return MM.deserialize(PREFIX + miniMessage);
    }

    public static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(prefixed(miniMessage));
    }

    public static void sendRaw(CommandSender sender, String miniMessage) {
        sender.sendMessage(parse(miniMessage));
    }

    /**
     * 전체 공지. permission 이 null 이면 모두에게, 아니면 해당 권한자에게만 보낸다.
     * 콘솔에는 항상 출력한다.
     */
    public static void broadcast(String miniMessage, String permission) {
        Component component = prefixed(miniMessage);
        Bukkit.getConsoleSender().sendMessage(component);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (permission == null || player.hasPermission(permission)) {
                player.sendMessage(component);
            }
        }
    }

    /** 사용자 입력이 MiniMessage 태그로 해석되지 않도록 정리한다. */
    public static String sanitize(String input) {
        if (input == null) return "";
        return input.replace('<', '(').replace('>', ')').replace('&', '+');
    }
}
