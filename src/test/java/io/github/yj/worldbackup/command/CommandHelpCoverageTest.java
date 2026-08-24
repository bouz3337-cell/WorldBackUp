package io.github.yj.worldbackup.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>등록한 명령은 전부 {@code /wb help} 에 있어야 한다.</b>
 *
 * <p>명령을 하나 붙일 때 빠뜨리기 가장 쉬운 것이 도움말이다. 그런데 빠져도 아무 데서도
 * 티가 나지 않는다 - 명령은 정상으로 돌고, 탭 완성에도 뜬다. 그저 <b>아무도 그런 명령이
 * 있는 줄 모를</b> 뿐이다. 백업 플러그인에서는 그것이 "쓸 수 있는 수단이 있는 줄 모르고
 * 서버를 잃는" 것과 같은 자리에 있다.</p>
 *
 * <p>실제로 {@code /wb oneback} 을 붙일 때 이 자리를 빠뜨렸고, 이 테스트가 없었으면
 * 그대로 나갔을 것이다.</p>
 */
class CommandHelpCoverageTest {

    private static final Path SOURCE =
            Path.of("src/main/java/io/github/yj/worldbackup/command/WorldBackUpCommand.java");

    /** {@code Commands.literal("이름")} - 명령 트리의 마디. 깊이는 가리지 않는다. */
    private static final Pattern LITERAL = Pattern.compile("Commands\\.literal\\(\"([a-z]+)\"\\)");

    /** {@code backupArgument("이름", ...)} 으로 붙는 것들도 명령이다. */
    private static final Pattern ARGUMENT = Pattern.compile("backupArgument\\(\"([a-z]+)\"");

    /** {@code line(sender, "/wb ...", ...)} - 도움말 한 줄이 보여 주는 사용법. */
    private static final Pattern HELP_USAGE = Pattern.compile("line\\(sender, \"([^\"]+)\"");

    /**
     * 검사에서 빼는 이름.
     *
     * <p>{@code worldbackup} 은 하위 명령이 아니라 명령 트리의 <b>뿌리</b>({@code /worldbackup})
     * 이고, {@code help} 는 도움말 자신이라 자기를 적을 이유가 없다.</p>
     */
    private static final Set<String> NOT_A_SUBCOMMAND = Set.of("worldbackup", "help");

    @Test
    void everyCommandAppearsInTheHelp() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Set<String> registered = registeredCommands(source);
        String help = String.join(" ", helpUsages(source));

        assertFalse(registered.isEmpty(), "명령을 하나도 찾지 못했다면 이 검사가 아무것도 지키지 못한다");
        for (String command : registered) {
            assertTrue(mentions(help, command),
                    "/wb " + command + " 가 도움말에 없다. 아무도 그런 명령이 있는 줄 모른다");
        }
    }

    /** 반대로, 없는 명령을 도움말이 약속하면 관리자는 되지 않는 것을 시도하게 된다. */
    @Test
    void theHelpNeverPromisesACommandThatDoesNotExist() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Set<String> registered = registeredCommands(source);

        for (String usage : helpUsages(source)) {
            // "/wb restore at [시각]" -> "restore"
            String[] words = usage.trim().split("\\s+");
            if (words.length < 2) continue;
            String first = words[1].replaceAll("[^a-z].*$", "");
            if (first.isEmpty()) continue;
            assertTrue(registered.contains(first),
                    "도움말에 있는 /wb " + first + " 이 실제로는 등록되지 않았다");
        }
    }

    // ------------------------------------------------------------------

    private static Set<String> registeredCommands(String source) {
        Set<String> registered = new LinkedHashSet<>();
        collect(LITERAL, source, registered);
        collect(ARGUMENT, source, registered);
        registered.removeAll(NOT_A_SUBCOMMAND);
        return registered;
    }

    private static Set<String> helpUsages(String source) {
        Set<String> usages = new LinkedHashSet<>();
        Matcher matcher = HELP_USAGE.matcher(source);
        while (matcher.find()) {
            String usage = matcher.group(1);
            if (usage.startsWith("/wb")) usages.add(usage);
        }
        return usages;
    }

    /** 낱말 단위로 본다. {@code list} 가 {@code listing} 에 걸려 통과하면 안 된다. */
    private static boolean mentions(String help, String command) {
        return Pattern.compile("(?<![a-z])" + Pattern.quote(command) + "(?![a-z])").matcher(help).find();
    }

    private static void collect(Pattern pattern, String source, Set<String> into) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) into.add(matcher.group(1));
    }
}
