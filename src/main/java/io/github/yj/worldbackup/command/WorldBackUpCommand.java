package io.github.yj.worldbackup.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.yj.worldbackup.WorldBackUpPlugin;
import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.BackupRepository;
import io.github.yj.worldbackup.backup.BackupType;
import io.github.yj.worldbackup.backup.RetentionTiers;
import io.github.yj.worldbackup.config.BackupSettings;
import io.github.yj.worldbackup.util.FileUtil;
import io.github.yj.worldbackup.util.Msg;
import io.github.yj.worldbackup.util.Sched;
import io.github.yj.worldbackup.util.TimeToken;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Brigadier 로 등록하는 {@code /worldbackup} 명령어.
 *
 * <p>레거시 {@code CommandExecutor} 대신 Paper 의 명령어 API 를 쓴다. 인자 단위로 타입과 권한이
 * 검증되고, 탭 완성이 서버가 아는 형태로 나간다. 플러그인 로딩 방식({@code plugin.yml})은
 * 그대로 두었다 - 복원이 월드 로드 전에 실행돼야 하는 핵심 보장을 건드리지 않기 위해서다.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class WorldBackUpCommand {

    private static final int PAGE_SIZE = 8;

    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final WorldBackUpPlugin plugin;

    /** 탭 완성용 백업 ID 캐시 (5초). 매번 디스크를 읽지 않기 위함. */
    private List<String> cachedIds = List.of();
    private long cachedAt;

    public WorldBackUpCommand(WorldBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 명령어 트리

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("worldbackup")
                .requires(source -> has(source.getSender(), "worldbackup.use"))
                .executes(ctx -> run(ctx, this::help))

                .then(Commands.literal("help").executes(ctx -> run(ctx, this::help)))

                .then(Commands.literal("backup")
                        .requires(source -> has(source.getSender(), "worldbackup.backup"))
                        .executes(ctx -> run(ctx, sender -> backup(sender, null)))
                        .then(Commands.argument("메모", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, sender ->
                                        backup(sender, StringArgumentType.getString(ctx, "메모"))))))

                .then(Commands.literal("list")
                        .executes(ctx -> run(ctx, sender -> list(sender, 1)))
                        // 날짜별 요약. 몇 주 전 것을 찾을 때 목록을 넘기는 대신 여기서 짚는다.
                        .then(Commands.literal("days").executes(ctx -> run(ctx, this::listDays)))
                        .then(Commands.argument("페이지", StringArgumentType.word())
                                .suggests(listSuggestions())
                                .executes(ctx -> run(ctx, sender ->
                                        listToken(sender, StringArgumentType.getString(ctx, "페이지"))))))

                .then(backupArgument("info", "worldbackup.use", this::info))
                .then(Commands.literal("restore")
                        .requires(source -> has(source.getSender(), "worldbackup.restore"))
                        // Brigadier 의 word() 는 콜론과 공백을 받지 못한다. "03:00" 이나
                        // "2026-08-16 03:00" 은 인자 파싱 단계에서 거부되므로 별도 통로를 둔다.
                        .then(Commands.literal("at")
                                .then(Commands.argument("시각", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, sender -> restoreAt(ctx, sender)))))
                        .then(Commands.argument("백업", StringArgumentType.word())
                                .suggests(backupSuggestions())
                                .executes(ctx -> run(ctx, sender -> restore(ctx, sender, false)))
                                .then(Commands.literal("worlds")
                                        .executes(ctx -> run(ctx, sender -> restore(ctx, sender, true))))))

                .then(Commands.literal("confirm")
                        .requires(source -> has(source.getSender(), "worldbackup.restore"))
                        .executes(ctx -> run(ctx, sender -> plugin.restoreService().confirm(sender))))
                .then(Commands.literal("cancel")
                        .requires(source -> has(source.getSender(), "worldbackup.restore"))
                        .executes(ctx -> run(ctx, sender -> plugin.restoreService().cancel(sender))))

                .then(Commands.literal("delete")
                        .requires(source -> has(source.getSender(), "worldbackup.delete"))
                        .then(Commands.argument("백업", StringArgumentType.word())
                                .suggests(backupSuggestions())
                                .executes(ctx -> run(ctx, sender -> delete(ctx, sender, false)))
                                .then(Commands.literal("cascade")
                                        .executes(ctx -> run(ctx, sender -> delete(ctx, sender, true))))))

                .then(backupArgument("lock", "worldbackup.delete", (sender, entry) -> setLocked(sender, entry, true)))
                .then(backupArgument("unlock", "worldbackup.delete", (sender, entry) -> setLocked(sender, entry, false)))

                .then(Commands.literal("prune")
                        .requires(source -> has(source.getSender(), "worldbackup.delete"))
                        .executes(ctx -> run(ctx, this::prune)))
                .then(Commands.literal("status").executes(ctx -> run(ctx, this::status)))
                .then(Commands.literal("reload")
                        .requires(source -> has(source.getSender(), "worldbackup.reload"))
                        .executes(ctx -> run(ctx, this::reload)))
                .build();
    }

    /** "<이름> <백업>" 형태의 하위 명령을 만든다. */
    private LiteralArgumentBuilder<CommandSourceStack> backupArgument(String name,
                                                                     String permission,
                                                                     BackupAction action) {
        return Commands.literal(name)
                .requires(source -> has(source.getSender(), permission))
                .then(Commands.argument("백업", StringArgumentType.word())
                        .suggests(backupSuggestions())
                        .executes(ctx -> run(ctx, sender ->
                                resolve(sender, StringArgumentType.getString(ctx, "백업"))
                                        .ifPresent(entry -> action.accept(sender, entry)))));
    }

    @FunctionalInterface
    private interface BackupAction {
        void accept(CommandSender sender, BackupEntry entry);
    }

    /** 시각 표현을 ID 보다 앞에 둔다. 사고를 발견한 사람이 아는 것은 ID 가 아니라 시각이다. */
    private static final List<String> TIME_HINTS = List.of("latest", "30m", "1h", "3h", "6h", "12h", "1d");

    /** 백업이 실제로 있는 날짜를 제안한다. 없는 날을 뒤지게 두지 않는다. */
    private SuggestionProvider<CommandSourceStack> listSuggestions() {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            if ("days".startsWith(prefix)) builder.suggest("days");
            plugin.repository().list().stream()
                    .map(entry -> entry.localDate().toString())
                    .distinct()
                    .filter(date -> date.startsWith(prefix))
                    .limit(30)
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> backupSuggestions() {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            for (String hint : TIME_HINTS) {
                if (hint.startsWith(prefix)) builder.suggest(hint);
            }
            for (String id : backupIds()) {
                if (id.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    /** 명령 본문을 감싸 예외가 서버 콘솔로 새어 나가지 않게 한다. */
    private int run(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<CommandSender> body) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            body.accept(sender);
        } catch (Exception e) {
            Msg.send(sender, "<red>명령 처리 중 오류가 발생했습니다: "
                    + Msg.sanitize(String.valueOf(e.getMessage())) + "</red>");
            plugin.getLogger().log(java.util.logging.Level.WARNING, "명령 처리 실패", e);
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission("worldbackup.admin");
    }

    // ------------------------------------------------------------------
    // 동작

    private void help(CommandSender sender) {
        Msg.sendRaw(sender, "<dark_gray>─────────</dark_gray> <gradient:#5eead4:#38bdf8><bold>WorldBackUp</bold></gradient> <dark_gray>─────────</dark_gray>");
        line(sender, "/wb backup [메모]", "지금 즉시 백업합니다");
        line(sender, "/wb list [페이지|날짜]", "백업 목록을 봅니다");
        line(sender, "/wb list days", "날짜별로 몇 개씩 있는지 봅니다");
        line(sender, "/wb info [ID|번호]", "백업 상세 정보를 봅니다");
        line(sender, "/wb restore [ID|번호] (worlds)", "그 시점으로 되돌립니다");
        line(sender, "/wb restore at [시각]", "시각으로 찾아 되돌립니다 (03:00, 9h)");
        line(sender, "/wb confirm", "복원을 확정합니다");
        line(sender, "/wb cancel", "복원 요청을 취소합니다");
        line(sender, "/wb delete [ID|번호] (cascade)", "백업을 삭제합니다");
        line(sender, "/wb lock, /wb unlock [ID|번호]", "자동 삭제로부터 보호/해제합니다");
        line(sender, "/wb prune", "보관 정책을 지금 적용합니다");
        line(sender, "/wb status", "현재 상태를 봅니다");
        line(sender, "/wb reload", "설정을 다시 불러옵니다");
        Msg.sendRaw(sender, "<dark_gray>번호는 <white>/wb list</white> 의 <white>#숫자</white>, <white>latest</white> 도 사용할 수 있습니다.</dark_gray>");
    }

    private void line(CommandSender sender, String usage, String description) {
        Msg.sendRaw(sender, " <aqua>" + usage + "</aqua> <dark_gray>-</dark_gray> <gray>" + description + "</gray>");
    }

    private void backup(CommandSender sender, String rawMemo) {
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>이미 백업이 진행 중입니다. <gray>(" + plugin.backupService().progressText() + ")</gray></red>");
            return;
        }
        String memo = rawMemo == null || rawMemo.isBlank() ? null : Msg.sanitize(rawMemo);
        Msg.send(sender, "<gray>백업을 시작합니다...</gray>");
        plugin.backupService().startAsync(BackupType.MANUAL, memo, sender)
                .whenComplete((entry, error) -> Sched.syncQuietly(plugin, () -> {
                    if (error != null) {
                        Msg.send(sender, "<red>백업 실패: " + Msg.sanitize(String.valueOf(error.getMessage())) + "</red>");
                        return;
                    }
                    Msg.send(sender, "<green>백업 완료</green> <white>" + entry.id() + "</white> <gray>("
                            + FileUtil.humanBytes(entry.archiveBytes()) + ", " + entry.fileCount() + "개 파일)</gray>");
                }));
    }

    /** {@code /wb list <값>} 의 값이 페이지 번호인지 날짜인지 갈라 준다. */
    private void listToken(CommandSender sender, String token) {
        if (token.matches("\\d{1,4}")) {
            list(sender, Integer.parseInt(token));
            return;
        }
        try {
            listOnDate(sender, LocalDate.parse(token));
        } catch (DateTimeParseException e) {
            Msg.send(sender, "<red>페이지 번호나 날짜를 넣어 주세요: <white>" + Msg.sanitize(token) + "</white></red>");
            Msg.send(sender, "<gray>예) <white>/wb list 3</white>, <white>/wb list 2026-08-01</white>, "
                    + "<white>/wb list days</white></gray>");
        }
    }

    /** 하루치만 본다. 사고 시각을 아는 날에 곧바로 들어갈 수 있어야 한다. */
    private void listOnDate(CommandSender sender, LocalDate day) {
        List<BackupEntry> entries = plugin.repository().list().stream()
                .filter(entry -> entry.localDate().equals(day))
                .toList();

        if (entries.isEmpty()) {
            Msg.send(sender, "<gray><white>" + day + "</white> 에 만들어진 백업이 없습니다.</gray>");
            Msg.send(sender, "<click:run_command:'/wb list days'><gray>» "
                    + "<white>/wb list days</white> 로 어느 날짜에 백업이 있는지 보세요.</gray></click>");
            return;
        }

        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>" + day + dayLabel(day) + "</aqua> <gray>("
                + entries.size() + "개)</gray>");
        for (BackupEntry entry : entries) {
            Msg.sendRaw(sender, "  " + entryLine(entry));
        }
        Msg.sendRaw(sender, "<click:run_command:'/wb list days'><dark_gray>» 날짜 목록으로</dark_gray></click>");
    }

    /**
     * 날짜별 요약.
     *
     * <p>몇 주 전 것을 찾을 때 목록을 여러 장 넘기는 대신 여기서 바로 짚는다.
     * 계단식 보관이 실제로 도는지도 이 화면에서 드러난다 - 오늘은 촘촘하고
     * 과거로 갈수록 개수가 줄어드는 모양이 보이면 계단이 동작하고 있는 것이다.</p>
     */
    private void listDays(CommandSender sender) {
        List<BackupEntry> entries = plugin.repository().list();
        if (entries.isEmpty()) {
            Msg.send(sender, "<gray>아직 백업이 없습니다.</gray>");
            return;
        }

        Map<LocalDate, List<BackupEntry>> byDay = new LinkedHashMap<>();
        for (BackupEntry entry : entries) {
            byDay.computeIfAbsent(entry.localDate(), key -> new ArrayList<>()).add(entry);
        }

        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>날짜별 백업</aqua> <gray>("
                + entries.size() + "개, " + byDay.size() + "일)</gray>");

        for (Map.Entry<LocalDate, List<BackupEntry>> day : byDay.entrySet()) {
            List<BackupEntry> ofDay = day.getValue(); // 최신순
            String newest = TIME_ONLY.format(ofDay.get(0).createdAt());
            String oldest = TIME_ONLY.format(ofDay.get(ofDay.size() - 1).createdAt());
            String span = ofDay.size() == 1 ? newest : oldest + "~" + newest;
            long bytes = ofDay.stream().mapToLong(BackupEntry::archiveBytes).sum();

            Msg.sendRaw(sender, "<hover:show_text:'<gray>클릭하면 이 날의 백업 목록</gray>'>"
                    + "<click:run_command:'/wb list " + day.getKey() + "'>"
                    + " <white>" + day.getKey() + "</white><dark_gray>" + dayLabel(day.getKey()) + "</dark_gray>"
                    + " <gray>" + ofDay.size() + "개</gray>"
                    + " <dark_gray>" + span + " · " + FileUtil.humanBytes(bytes) + "</dark_gray>"
                    + "</click></hover>");
        }
        Msg.sendRaw(sender, "<dark_gray>날짜를 클릭하거나 <white>/wb restore at 2026-08-01 03:00</white> "
                + "처럼 시각으로 바로 되돌릴 수 있습니다.</dark_gray>");
    }

    private void list(CommandSender sender, int requestedPage) {
        List<BackupEntry> entries = plugin.repository().list();
        if (entries.isEmpty()) {
            Msg.send(sender, "<gray>아직 백업이 없습니다. <white>/wb backup</white> 으로 첫 백업을 만들어 보세요.</gray>");
            return;
        }

        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = Math.min(Math.max(1, requestedPage), pages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);

        long totalBytes = entries.stream().mapToLong(BackupEntry::archiveBytes).sum();
        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>백업 목록</aqua> <gray>(" + entries.size() + "개, "
                + FileUtil.humanBytes(totalBytes) + ")</gray> <dark_gray>[" + page + "/" + pages + "]</dark_gray>");

        // 몇 주 전 것을 찾을 수 있는지부터 알려 준다. 없는 시점을 뒤지느라 시간을 쓰지 않도록.
        BackupEntry oldest = entries.get(entries.size() - 1);
        BackupEntry newest = entries.get(0);
        Msg.sendRaw(sender, "<dark_gray>보관 범위: " + oldest.displayTime() + " ~ " + newest.displayTime()
                + " (" + FileUtil.humanDuration(Duration.between(oldest.createdAt(), newest.createdAt())) + ")</dark_gray>");

        LocalDate previousDay = null;
        for (int i = from; i < to; i++) {
            BackupEntry entry = entries.get(i);

            // 날짜가 바뀌면 머리글을 넣는다. 사고 시점을 찾을 때 날짜 경계가 보여야 훑기 쉽다.
            LocalDate day = entry.localDate();
            if (!day.equals(previousDay)) {
                previousDay = day;
                Msg.sendRaw(sender, "<dark_gray>  " + day + dayLabel(day) + "</dark_gray>");
            }

            Msg.sendRaw(sender, "<dark_gray>#" + (i + 1) + "</dark_gray> " + entryLine(entry));
        }

        StringBuilder nav = new StringBuilder();
        if (page > 1) {
            nav.append("<click:run_command:'/wb list ").append(page - 1).append("'><gray>« 이전</gray></click>  ");
        }
        if (page < pages) {
            nav.append("<click:run_command:'/wb list ").append(page + 1).append("'><gray>다음 »</gray></click>  ");
        }
        nav.append("<click:run_command:'/wb list days'><dark_gray>[날짜별로 보기]</dark_gray></click>");
        Msg.sendRaw(sender, nav.toString());
    }

    /** 목록 한 줄. 페이지 목록과 날짜별 목록이 같은 모양을 쓰도록 한 곳에 둔다. */
    private String entryLine(BackupEntry entry) {
        String age = FileUtil.humanDuration(Duration.between(entry.createdAt(), Instant.now()));
        String memo = entry.hasLabel()
                ? " <dark_gray>| <italic>" + Msg.sanitize(entry.label()) + "</italic></dark_gray>" : "";
        String tags = entry.locked() ? " <gold>[보호]</gold>" : "";
        if (entry.isDifferential()) tags += " <yellow>[차등]</yellow>";
        if (!entry.complete()) tags += " <red>[손상]</red>";
        if (!entry.hasPlayerData()) {
            tags += entry.playerDataUnknown() ? " <yellow>[플레이어?]</yellow>" : " <red>[플레이어없음]</red>";
        }
        return "<hover:show_text:'<gray>클릭하면 상세 정보</gray>'><click:run_command:'/wb info " + entry.id() + "'>"
                + "<white>" + entry.displayTime() + "</white></click></hover> "
                + "<dark_gray>|</dark_gray> " + entry.type().color() + entry.type().korean()
                + "</" + colorTag(entry.type().color()) + "> "
                + "<dark_gray>|</dark_gray> <aqua>" + FileUtil.humanBytes(entry.archiveBytes()) + "</aqua> "
                + "<dark_gray>|</dark_gray> <gray>" + age + " 전</gray>" + tags + memo;
    }

    private String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (day.equals(today)) return " (오늘)";
        if (day.equals(today.minusDays(1))) return " (어제)";
        return "";
    }

    private String colorTag(String openTag) {
        return openTag.replace("<", "").replace(">", "");
    }

    /**
     * 이 백업으로 인벤토리를 되돌릴 수 있는지.
     *
     * <p>"모름" 을 "포함" 으로 뭉개지 않는다. 사고가 나서 롤백한 뒤에 인벤토리가 그대로인 것을
     * 알게 되는 것이 최악이라, 확실하지 않으면 확실하지 않다고 말한다.</p>
     */
    private String playerDataLabel(BackupEntry entry) {
        if (entry.hasPlayerData()) {
            return "<green>포함</green> <dark_gray>(인벤토리·경험치·통계)</dark_gray>";
        }
        if (entry.playerDataUnknown()) {
            return "<yellow>알 수 없음</yellow> <dark_gray>(옛 버전으로 만든 백업)</dark_gray>";
        }
        return "<red><bold>미포함 - 인벤토리를 되돌릴 수 없습니다</bold></red>";
    }

    private void info(CommandSender sender, BackupEntry entry) {
        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>백업 정보</aqua> <dark_gray>─────</dark_gray>");
        Msg.sendRaw(sender, " <gray>ID       :</gray> <white>" + entry.id() + "</white>");
        if (!entry.complete()) {
            Msg.sendRaw(sender, " <red><bold>복원할 수 없는 백업입니다.</bold></red>");
            Msg.sendRaw(sender, entry.isDifferential()
                    ? " <gray>차등 백업인데 기준이 되는 전체 백업이 사라졌습니다.</gray>"
                    : " <gray>압축이 끝나기 전에 서버가 종료된 것으로 보입니다.</gray>");
        }
        Msg.sendRaw(sender, " <gray>시각     :</gray> <white>" + entry.displayTime() + "</white> <dark_gray>("
                + FileUtil.humanDuration(Duration.between(entry.createdAt(), Instant.now())) + " 전)</dark_gray>");
        Msg.sendRaw(sender, " <gray>종류     :</gray> " + entry.type().color() + entry.type().korean() + "</"
                + colorTag(entry.type().color()) + ">" + (entry.locked() ? " <gold>[보호됨]</gold>" : ""));
        Msg.sendRaw(sender, " <gray>방식     :</gray> " + (entry.isDifferential()
                ? "<yellow>차등</yellow> <dark_gray>(기준 " + entry.baseId() + ")</dark_gray>"
                : "<white>전체</white>"));
        if (entry.hasLabel()) {
            Msg.sendRaw(sender, " <gray>메모     :</gray> <white>" + Msg.sanitize(entry.label()) + "</white>");
        }
        Msg.sendRaw(sender, " <gray>파일 크기:</gray> <aqua>" + FileUtil.humanBytes(entry.archiveBytes())
                + "</aqua> <dark_gray>(스냅샷 원본 " + FileUtil.humanBytes(entry.originalBytes()) + ")</dark_gray>");
        Msg.sendRaw(sender, " <gray>파일 수  :</gray> <white>" + entry.fileCount() + "</white>");
        Msg.sendRaw(sender, " <gray>플레이어 :</gray> " + playerDataLabel(entry));
        Msg.sendRaw(sender, " <gray>월드     :</gray> <white>"
                + (entry.worlds().isEmpty() ? "-" : String.join(", ", entry.worlds())) + "</white>");
        Msg.sendRaw(sender, " <gray>포함 경로:</gray> <white>"
                + (entry.roots().isEmpty() ? "-" : String.join(", ", entry.roots())) + "</white>");
        Msg.sendRaw(sender, " <gray>서버     :</gray> <white>" + entry.serverVersion() + "</white>");
        if (entry.complete() && sender.hasPermission("worldbackup.restore")) {
            Msg.sendRaw(sender, " <click:suggest_command:'/wb restore " + entry.id()
                    + "'><green>[이 시점으로 복원하기]</green></click>");
        }
    }

    private void restore(CommandContext<CommandSourceStack> ctx, CommandSender sender, boolean worldsOnly) {
        resolve(sender, StringArgumentType.getString(ctx, "백업"))
                .ifPresent(entry -> plugin.restoreService().request(sender, entry, worldsOnly));
    }

    /**
     * {@code /wb restore at <시각>} - 콜론과 공백이 든 시각 표현을 받는다.
     *
     * <p>일반 인자로는 {@code 03:00} 을 칠 수 없다. Brigadier 가 인용 없는 문자열에서
     * 콜론과 공백을 허용하지 않기 때문이다. 사고를 발견한 사람이 가장 자연스럽게 떠올리는
     * 형식이라 통로를 따로 열어 둔다.</p>
     */
    private void restoreAt(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        String raw = StringArgumentType.getString(ctx, "시각").trim();
        Instant target = TimeToken.parse(raw);
        if (target == null) {
            Msg.send(sender, "<red>시각을 알아듣지 못했습니다: <white>" + Msg.sanitize(raw) + "</white></red>");
            Msg.send(sender, "<gray>예) <white>/wb restore at 03:00</white>, "
                    + "<white>/wb restore at 2026-08-16 03:00</white>, <white>/wb restore at 9h</white></gray>");
            return;
        }
        resolveAt(sender, target).ifPresent(entry -> plugin.restoreService().request(sender, entry, false));
    }

    private void delete(CommandContext<CommandSourceStack> ctx, CommandSender sender, boolean cascade) {
        Optional<BackupEntry> found = resolve(sender, StringArgumentType.getString(ctx, "백업"));
        if (found.isEmpty()) return;
        BackupEntry entry = found.get();

        if (entry.locked()) {
            Msg.send(sender, "<red>보호된 백업입니다. <white>/wb unlock " + entry.id() + "</white> 후 삭제하세요.</red>");
            return;
        }
        if (plugin.repository().isPinned(entry)) {
            Msg.send(sender, "<red>지금 만들어지는 차등 백업이 이 백업을 기준으로 삼고 있습니다.</red>");
            Msg.send(sender, "<gray>백업이 끝난 뒤 다시 시도하세요.</gray>");
            return;
        }

        List<BackupEntry> all = plugin.repository().list();
        List<BackupEntry> dependents = plugin.repository().dependents(all, entry.id());

        // 차등 백업은 기준 백업 없이는 복원할 수 없다. 즉 기준을 지우는 것은 딸린 차등본을
        // 지우는 것과 같으므로, 보호된 차등본이 하나라도 있으면 cascade 여부와 무관하게 거부한다.
        // (이 검사가 없으면 /wb lock 으로 잠근 백업이 cascade 한 번에 사라진다)
        List<BackupEntry> lockedDependents = dependents.stream().filter(BackupEntry::locked).toList();
        if (!lockedDependents.isEmpty()) {
            Msg.send(sender, "<red>보호된 차등 백업 " + lockedDependents.size()
                    + "개가 이 백업을 기준으로 삼고 있어 삭제할 수 없습니다.</red>");
            for (BackupEntry dependent : lockedDependents) {
                Msg.send(sender, "<gray> - <white>" + dependent.id() + "</white> <gold>[보호]</gold></gray>");
            }
            Msg.send(sender, "<gray>먼저 <white>/wb unlock [ID]</white> 로 보호를 해제하세요.</gray>");
            return;
        }

        if (!dependents.isEmpty() && !cascade) {
            Msg.send(sender, "<red>이 백업을 기준으로 삼는 차등 백업이 " + dependents.size() + "개 있습니다.</red>");
            Msg.send(sender, "<gray>지우면 그 백업들도 복원할 수 없게 됩니다. 함께 지우려면 "
                    + "<white>/wb delete " + entry.id() + " cascade</white></gray>");
            return;
        }

        int deleted = 0;
        if (cascade) {
            for (BackupEntry dependent : dependents) {
                if (plugin.repository().delete(dependent)) deleted++;
            }
        }
        if (plugin.repository().delete(entry)) {
            deleted++;
            Msg.send(sender, "<green>백업 " + deleted + "개를 삭제했습니다: <white>" + entry.id() + "</white></green>");
        } else {
            Msg.send(sender, "<red>백업 삭제에 실패했습니다. 콘솔 로그를 확인하세요.</red>");
        }
    }

    private void setLocked(CommandSender sender, BackupEntry entry, boolean locked) {
        if (!plugin.repository().setLocked(entry, locked)) {
            Msg.send(sender, "<red>보호 상태를 저장하지 못했습니다. 콘솔 로그를 확인하세요.</red>");
            return;
        }
        Msg.send(sender, locked
                ? "<green>이제 이 백업은 자동으로 삭제되지 않습니다: <white>" + entry.id() + "</white></green>"
                : "<gray>보호를 해제했습니다: <white>" + entry.id() + "</white></gray>");
    }

    private void prune(CommandSender sender) {
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>백업이 진행 중입니다. 완료 후 다시 시도하세요.</red>");
            return;
        }
        Msg.send(sender, "<gray>보관 정책을 적용하는 중입니다...</gray>");
        BackupSettings settings = plugin.settings();
        Sched.async(plugin, () -> {
            BackupRepository.PruneResult result = plugin.repository().prune(settings);
            Sched.syncQuietly(plugin, () -> {
                if (result.deleted() == 0) {
                    Msg.send(sender, "<gray>삭제할 백업이 없습니다.</gray>");
                } else {
                    Msg.send(sender, "<green>백업 " + result.deleted() + "개를 삭제했습니다.</green> <gray>("
                            + FileUtil.humanBytes(result.freedBytes()) + " 확보)</gray>");
                }
            });
        });
    }

    private void status(CommandSender sender) {
        BackupSettings settings = plugin.settings();
        List<BackupEntry> entries = plugin.repository().list();
        long totalBytes = entries.stream().mapToLong(BackupEntry::archiveBytes).sum();

        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>WorldBackUp 상태</aqua> <dark_gray>─────</dark_gray>");
        if (plugin.restoreFailureHold()) {
            Msg.sendRaw(sender, " <red><bold>복원 실패 기록이 남아 자동 작업이 멈춰 있습니다.</bold></red>");
            Msg.sendRaw(sender, " <gray>plugins/WorldBackUp/restore-failed-*.yml 을 확인하고 지운 뒤 "
                    + "<white>/wb reload</white> 하세요.</gray>");
        }
        Msg.sendRaw(sender, " <gray>자동 백업:</gray> " + (settings.enabled()
                ? "<green>켜짐</green> <dark_gray>(" + settings.intervalMinutes() + "분 주기)</dark_gray>"
                : "<red>꺼짐</red>"));
        Msg.sendRaw(sender, " <gray>백업 방식:</gray> " + (settings.differential()
                ? "<yellow>차등</yellow> <dark_gray>(전체 백업 " + settings.fullEvery() + "회마다 재생성)</dark_gray>"
                : "<white>전체</white>"));

        // 인벤토리가 빠진 채 백업이 "성공" 하는 상황을 눈에 띄게 만든다.
        boolean inventory = plugin.locatePlayerData().inventory();
        Msg.sendRaw(sender, " <gray>플레이어  :</gray> " + (inventory
                ? "<green>포함</green> <dark_gray>(인벤토리·경험치·통계)</dark_gray>"
                : "<red><bold>미포함 - 인벤토리를 되돌릴 수 없습니다</bold></red>"));

        if (plugin.backupService().isRunning()) {
            String progress = plugin.backupService().progressText();
            Msg.sendRaw(sender, " <gray>진행 중  :</gray> <yellow>" + (progress.isBlank() ? "준비 중" : progress) + "</yellow>");
        } else if (settings.enabled()) {
            long remain = plugin.backupService().nextRunAt() - System.currentTimeMillis();
            Msg.sendRaw(sender, " <gray>다음 백업:</gray> <white>"
                    + (remain > 0 ? FileUtil.humanDuration(Duration.ofMillis(remain)) + " 후" : "곧") + "</white>");
        }

        plugin.backupService().lastBackup().ifPresentOrElse(
                entry -> Msg.sendRaw(sender, " <gray>최근 백업:</gray> <white>" + entry.displayTime() + "</white> <dark_gray>("
                        + FileUtil.humanBytes(entry.archiveBytes()) + ")</dark_gray>"),
                () -> {
                    if (!entries.isEmpty()) {
                        BackupEntry entry = entries.get(0);
                        Msg.sendRaw(sender, " <gray>최근 백업:</gray> <white>" + entry.displayTime() + "</white> <dark_gray>("
                                + FileUtil.humanBytes(entry.archiveBytes()) + ")</dark_gray>");
                    }
                });
        plugin.backupService().lastError().ifPresent(error ->
                Msg.sendRaw(sender, " <gray>최근 오류:</gray> <red>" + Msg.sanitize(error) + "</red>"));

        Msg.sendRaw(sender, " <gray>보관 중  :</gray> <white>" + entries.size() + "개</white> <dark_gray>("
                + FileUtil.humanBytes(totalBytes) + ")</dark_gray>");
        Msg.sendRaw(sender, " <gray>저장 위치:</gray> <white>" + settings.backupDir() + "</white>");
        Msg.sendRaw(sender, " <gray>디스크   :</gray> <white>"
                + FileUtil.humanBytes(FileUtil.usableSpace(settings.backupDir())) + " 남음</white>");
        // 계단을 켜면 max-backups/max-age-days 는 동작하지 않는다. 그걸 그대로 보여 주면
        // 실제로 적용되지 않는 값을 보고 판단하게 된다.
        if (settings.tiers().isEmpty()) {
            Msg.sendRaw(sender, " <gray>보관 정책:</gray> <white>최대 " + settings.maxBackups() + "개 / "
                    + settings.maxAgeDays() + "일</white>");
        } else {
            int planned = settings.tiers().stream().mapToInt(RetentionTiers.Tier::keep).sum();
            Msg.sendRaw(sender, " <gray>보관 정책:</gray> <white>계단식 " + settings.tiers().size()
                    + "단계</white> <dark_gray>(최대 " + planned + "개)</dark_gray>");

            // 각 계단은 앞 계단이 끝난 지점부터 이어서 과거로 간다. 그 누적 범위를 직접 보여 준다.
            // 설정만 보고는 "6시간마다 1개" 가 실제로 어느 시간대를 덮는지 알기 어렵다.
            Duration covered = Duration.ZERO;
            for (RetentionTiers.Tier tier : settings.tiers()) {
                Duration span = tier.every().isZero()
                        // 간격 0 은 개수 기준이라 백업 주기로 어림잡는다.
                        ? Duration.ofMinutes((long) settings.intervalMinutes() * tier.keep())
                        : tier.every().multipliedBy(tier.keep());
                covered = covered.plus(span);

                String every = tier.every().isZero()
                        ? "최신 " + tier.keep() + "개"
                        : FileUtil.humanDuration(tier.every()) + "마다 × " + tier.keep() + "개";
                Msg.sendRaw(sender, "   <dark_gray>· " + every
                        + " <white>→ " + FileUtil.humanDuration(covered) + "까지</white></dark_gray>");
            }
            Msg.sendRaw(sender, "   <dark_gray>(간격 0 구간은 백업 주기 "
                    + settings.intervalMinutes() + "분으로 어림잡은 값입니다)</dark_gray>");
        }
        if (settings.minBackups() > 0) {
            Msg.sendRaw(sender, " <gray>최소 보관:</gray> <white>" + settings.minBackups() + "개</white>");
        }
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadPlugin();
            Msg.send(sender, "<green>설정을 다시 불러왔습니다.</green>");
        } catch (Exception e) {
            Msg.send(sender, "<red>설정을 불러오지 못했습니다: " + Msg.sanitize(String.valueOf(e.getMessage())) + "</red>");
        }
    }

    // ------------------------------------------------------------------

    /**
     * 토큰을 백업 하나로 푼다.
     *
     * <p>{@code 9h}, {@code 03:00} 처럼 시각을 말하면 <b>그 시점 이전</b>의 가장 최근 백업을
     * 고르고, 무엇을 골랐는지 알려 준다. 사고가 난 시각을 말했는데 그 이후 백업이 잡히면
     * 피해가 담긴 상태로 되돌리게 되므로, 요청을 넘기느니 조금 더 과거로 간다.</p>
     */
    private Optional<BackupEntry> resolve(CommandSender sender, String token) {
        Instant target = TimeToken.parse(token);
        if (target != null) return resolveAt(sender, target);

        Optional<BackupEntry> found = plugin.repository().resolve(token);
        if (found.isEmpty()) {
            Msg.send(sender, "<red>백업을 찾을 수 없습니다: <white>" + Msg.sanitize(token) + "</white></red>");
            Msg.send(sender, "<gray>ID·<white>#번호</white>·<white>latest</white> 외에 "
                    + "<white>9h</white>(9시간 전), <white>03:00</white>(그 시각) 도 됩니다.</gray>");
        }
        return found;
    }

    /** 이 시각 이전의 가장 최근 백업을 고르고, 무엇을 골랐는지 알려 준다. */
    private Optional<BackupEntry> resolveAt(CommandSender sender, Instant target) {
        Optional<BackupEntry> found = plugin.repository().resolveAtOrBefore(target);
        if (found.isEmpty()) {
            Msg.send(sender, "<red><white>" + BackupEntry.DISPLAY_FORMAT.format(target)
                    + "</white> 이전의 백업이 없습니다.</red>");
            Msg.send(sender, "<gray>가장 오래된 백업보다 더 과거를 요청하셨습니다. "
                    + "<white>/wb list</white> 로 보관 범위를 확인하세요.</gray>");
            return found;
        }
        BackupEntry entry = found.get();
        String gap = FileUtil.humanDuration(Duration.between(entry.createdAt(), target));
        Msg.send(sender, "<gray>요청 <white>" + BackupEntry.DISPLAY_FORMAT.format(target)
                + "</white> → 그 이전 가장 최근 백업 <white>" + entry.displayTime()
                + "</white> <dark_gray>(" + gap + " 더 과거)</dark_gray></gray>");
        return found;
    }

    private List<String> backupIds() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > 5_000L) {
            cachedIds = plugin.repository().list().stream().map(BackupEntry::id).toList();
            cachedAt = now;
        }
        return cachedIds;
    }
}
