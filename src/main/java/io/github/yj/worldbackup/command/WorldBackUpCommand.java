package io.github.yj.worldbackup.command;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.yj.worldbackup.WorldBackUpPlugin;
import io.github.yj.worldbackup.backup.BackupEntry;
import io.github.yj.worldbackup.backup.OneBack;
import io.github.yj.worldbackup.update.UpdateService;
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
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

    /** 채팅창이 넘치지 않도록 한 화면에 뿌릴 최대 줄 수. */
    private static final int DAY_VIEW_LIMIT = 20;
    private static final int DAY_LIST_LIMIT = 30;

    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final WorldBackUpPlugin plugin;

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

                .then(Commands.literal("update")
                        .requires(source -> has(source.getSender(), "worldbackup.reload"))
                        .executes(ctx -> run(ctx, this::update)))

                // 서버 한 벌을 통째로. 평소 백업과 목적이 달라 따로 둔다.
                .then(Commands.literal("oneback")
                        .requires(source -> has(source.getSender(), "worldbackup.backup"))
                        .executes(ctx -> run(ctx, this::oneBack)))

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
                        // 인자 없이 치면 시점 목록을 띄운다. 사고 직후에 ID 를 외우고 있을 리 없다.
                        .executes(ctx -> run(ctx, this::restoreMenu))
                        // Brigadier 의 word() 는 콜론과 공백을 받지 못한다. "03:00" 이나
                        // "2026-08-16 03:00" 은 인자 파싱 단계에서 거부되므로 별도 통로를 둔다.
                        .then(Commands.literal("at")
                                .executes(ctx -> run(ctx, this::needTimeArgument))
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
                        .executes(ctx -> run(ctx, sender -> needBackupArgument(sender, "delete")))
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
                // 인자를 빼먹었을 때 Brigadier 의 기계적인 오류 대신 무엇을 넣어야 하는지 알려 준다.
                .executes(ctx -> run(ctx, sender -> needBackupArgument(sender, name)))
                .then(Commands.argument("백업", StringArgumentType.word())
                        .suggests(backupSuggestions())
                        .executes(ctx -> run(ctx, sender ->
                                resolve(sender, StringArgumentType.getString(ctx, "백업"))
                                        .ifPresent(entry -> action.accept(sender, entry)))));
    }

    /**
     * 어떤 백업을 가리키는지 말하지 않았을 때.
     *
     * <p>Brigadier 는 이럴 때 커서 위치를 가리키는 영어 오류를 내놓는다. 급한 상황에서
     * 그것만 보고 다음에 무엇을 쳐야 할지 알 수는 없으므로, 고르는 방법을 그대로 보여 준다.</p>
     */
    private void needBackupArgument(CommandSender sender, String name) {
        Msg.send(sender, "<red>어떤 백업인지 알려 주세요: <white>/wb " + name + " [ID|#번호]</white></red>");
        Msg.send(sender, "<gray><white>latest</white>(가장 최근) · <white>9h</white>(9시간 전) · "
                + "<white>03:00</white>(그 시각) 도 됩니다.</gray>");
        Msg.sendRaw(sender, "<click:run_command:'/wb list'><dark_gray>» "
                + "<white>/wb list</white> 로 목록을 봅니다</dark_gray></click>");
    }

    /** {@code /wb restore at} 만 치고 시각을 빼먹었을 때. */
    private void needTimeArgument(CommandSender sender) {
        Msg.send(sender, "<red>되돌릴 시각을 알려 주세요: <white>/wb restore at [시각]</white></red>");
        Msg.send(sender, "<gray>예) <white>03:00</white>, <white>2026-08-16 03:00</white>, "
                + "<white>9h</white></gray>");
        Msg.sendRaw(sender, "<click:run_command:'/wb restore'><dark_gray>» "
                + "<white>/wb restore</white> 로 시점을 골라서 되돌립니다</dark_gray></click>");
    }

    @FunctionalInterface
    private interface BackupAction {
        void accept(CommandSender sender, BackupEntry entry);
    }

    /**
     * 탭 완성에 띄우는 시각 표현.
     *
     * @param token 실제로 입력될 문자열
     * @param label 사람이 읽는 이름
     * @param back  얼마나 과거인지. {@code null} 이면 "가장 최근".
     */
    record TimeHint(String token, String label, Duration back) {

        /**
         * 이 토큰이 <b>실제로</b> 가리키는 백업.
         *
         * <p>{@link BackupRepository#resolveAtOrBefore(Instant)} 와 <b>같은 규칙</b>이어야 한다.
         * 설명에 적힌 시각과 실제로 복원되는 시각이 어긋나면, 툴팁을 믿고 고른 사람이
         * 엉뚱한 시점으로 되돌아간다.</p>
         */
        BackupEntry resolve(List<BackupEntry> newestFirst, Instant now) {
            if (back == null) {
                return newestFirst.isEmpty() ? null : newestFirst.get(0);
            }
            Instant target = now.minus(back);
            for (BackupEntry entry : newestFirst) { // 최신순이라 첫 항목이 가장 가까운 과거
                if (entry.complete() && !entry.createdAt().isAfter(target)) return entry;
            }
            return null;
        }
    }

    /** 시각 표현을 ID 보다 앞에 둔다. 사고를 발견한 사람이 아는 것은 ID 가 아니라 시각이다. */
    static final List<TimeHint> TIME_HINTS = List.of(
            new TimeHint("latest", "가장 최근", null),
            new TimeHint("30m", "30분 전", Duration.ofMinutes(30)),
            new TimeHint("1h", "1시간 전", Duration.ofHours(1)),
            new TimeHint("3h", "3시간 전", Duration.ofHours(3)),
            new TimeHint("6h", "6시간 전", Duration.ofHours(6)),
            new TimeHint("12h", "12시간 전", Duration.ofHours(12)),
            new TimeHint("1d", "1일 전", Duration.ofDays(1)),
            new TimeHint("3d", "3일 전", Duration.ofDays(3)),
            new TimeHint("7d", "7일 전", Duration.ofDays(7)));

    /** 아무것도 입력하지 않았을 때 보여 줄 백업 ID 개수. */
    private static final int IDLE_ID_SUGGESTIONS = 5;
    /** ID 를 직접 치기 시작했을 때의 개수. */
    private static final int TYPED_ID_SUGGESTIONS = 25;
    private static final int DAY_SUGGESTIONS = 20;

    /**
     * 백업이 실제로 있는 날짜를 제안한다. 없는 날을 뒤지게 두지 않는다.
     *
     * <p>날짜만 늘어놓으면 어느 날에 무엇이 얼마나 있는지 알 수 없어 결국 하나씩 눌러 봐야 한다.
     * 개수·시간대·용량을 설명에 함께 띄운다.</p>
     */
    private SuggestionProvider<CommandSourceStack> listSuggestions() {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            StringRange range = range(builder);
            List<Suggestion> out = new ArrayList<>();

            if ("days".startsWith(prefix)) {
                out.add(hint(range, "days", "날짜별 보유 현황 - 어느 날짜에 몇 개가 있는지"));
            }

            Map<LocalDate, List<BackupEntry>> byDay = new LinkedHashMap<>(); // 최신순
            for (BackupEntry entry : plugin.repository().list()) {
                byDay.computeIfAbsent(entry.localDate(), key -> new ArrayList<>()).add(entry);
            }

            int shown = 0;
            for (Map.Entry<LocalDate, List<BackupEntry>> day : byDay.entrySet()) {
                if (shown >= DAY_SUGGESTIONS) break;
                String date = day.getKey().toString();
                if (!date.startsWith(prefix)) continue;
                out.add(hint(range, date, describeDay(day.getKey(), day.getValue())));
                shown++;
            }
            return ordered(builder, out);
        };
    }

    /**
     * 백업을 고르는 인자의 탭 완성.
     *
     * <p>예전에는 시각 표현과 백업 ID 를 그냥 쏟아 냈다. Brigadier 가 알파벳순으로 정렬하는 탓에
     * {@code 12h}, {@code 1d}, {@code 20260817-091500}, {@code 30m} 처럼 종류가 뒤섞여 나왔고,
     * 어느 줄이 무슨 뜻인지도 알 수 없었다. 여기서는 세 가지를 지킨다.</p>
     * <ul>
     *   <li><b>종류를 묶는다.</b> 시각 표현이 먼저, 그다음이 ID 다. 정렬을 피하려고
     *       {@link Suggestions} 를 직접 만든다.</li>
     *   <li><b>무엇을 가리키는지 적는다.</b> 각 줄에 그 토큰이 실제로 고르게 될 백업의 시각과
     *       크기가 붙는다. 눌렀을 때 어디로 가는지 모르는 채로 고르게 두지 않는다.</li>
     *   <li><b>ID 로 화면을 덮지 않는다.</b> 아무것도 입력하지 않았을 때는 최근 몇 개만 보이고,
     *       ID 를 직접 치기 시작하면 그때 넓게 찾는다.</li>
     * </ul>
     */
    private SuggestionProvider<CommandSourceStack> backupSuggestions() {
        return (ctx, builder) -> {
            String prefix = builder.getRemainingLowerCase();
            StringRange range = range(builder);
            List<Suggestion> out = new ArrayList<>();
            List<BackupEntry> entries = plugin.repository().list();
            Instant now = Instant.now();

            // 1) 시각 표현
            //
            // 훑어보는 중(입력 없음)일 때는 같은 백업을 가리키는 줄을 하나로 합친다. 보관이
            // 성긴 구간에서는 여러 시점이 같은 백업 하나로 모이는데, 그걸 다 늘어놓으면
            // 고를 것이 많은 것처럼 보인다. 반대로 이미 무언가 치고 있다면 그 사람은 특정
            // 토큰을 완성하려는 것이므로 합치지 않는다 - /wb restore 30m 은 언제나 유효하다.
            boolean browsing = prefix.isEmpty();
            Set<String> pointsTo = new HashSet<>();
            for (TimeHint time : TIME_HINTS) {
                if (!time.token().startsWith(prefix)) continue;
                BackupEntry match = time.resolve(entries, now);
                if (match == null) continue; // 보관 범위 밖이라 고를 수 없다
                if (browsing && !pointsTo.add(match.id())) continue;
                out.add(hint(range, time.token(), time.label() + " → " + describe(match)));
            }

            // 2) 백업 ID
            int limit = browsing ? IDLE_ID_SUGGESTIONS : TYPED_ID_SUGGESTIONS;
            int shown = 0;
            for (BackupEntry entry : entries) {
                if (shown >= limit) break;
                if (!entry.id().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
                out.add(hint(range, entry.id(), describe(entry)));
                shown++;
            }
            return ordered(builder, out);
        };
    }

    /** 탭 완성 한 줄에 붙는 설명. 색은 못 쓰지만 평문만으로 충분하다. */
    private static Suggestion hint(StringRange range, String text, String tooltip) {
        return new Suggestion(range, text, new LiteralMessage(tooltip));
    }

    /**
     * 우리가 정한 순서 그대로 내보낸다.
     *
     * <p>{@link SuggestionsBuilder#buildFuture()} 는 결과를 알파벳순으로 정렬해 버려서
     * 시각 표현과 ID 가 뒤섞인다. 순서가 곧 분류이므로 {@link Suggestions} 를 직접 만든다.</p>
     */
    private static CompletableFuture<Suggestions> ordered(SuggestionsBuilder builder, List<Suggestion> list) {
        return CompletableFuture.completedFuture(new Suggestions(range(builder), List.copyOf(list)));
    }

    private static StringRange range(SuggestionsBuilder builder) {
        return StringRange.between(builder.getStart(), builder.getInput().length());
    }

    /** "언제 · 얼마나 · 어떤 백업인지" 한 줄. 되돌리기 전에 알아야 할 것만 담는다. */
    static String describe(BackupEntry entry) {
        StringBuilder text = new StringBuilder(entry.displayTime())
                .append(" · ").append(FileUtil.humanBytes(entry.archiveBytes()))
                .append(" · ").append(entry.type().korean());
        if (entry.isDifferential()) text.append(" · 차등");
        if (entry.locked()) text.append(" · 보호");
        if (!entry.complete()) text.append(" · 손상(복원 불가)");
        if (!entry.hasPlayerData()) {
            text.append(entry.playerDataUnknown() ? " · 플레이어?" : " · 플레이어 없음");
        }
        return text.toString();
    }

    /** 그 날짜에 무엇이 얼마나 있는지 한 줄. */
    static String describeDay(LocalDate day, List<BackupEntry> ofDay) {
        String label = dayLabel(day).trim(); // " (오늘)" -> "(오늘)"
        String newest = TIME_ONLY.format(ofDay.get(0).createdAt());
        String oldest = TIME_ONLY.format(ofDay.get(ofDay.size() - 1).createdAt());
        String span = ofDay.size() == 1 ? newest : oldest + "~" + newest;
        long bytes = ofDay.stream().mapToLong(BackupEntry::archiveBytes).sum();
        return (label.isEmpty() ? "" : label + " · ") + ofDay.size() + "개 · "
                + span + " · " + FileUtil.humanBytes(bytes);
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
        line(sender, "/wb oneback", "서버 한 벌을 통째로 담습니다 (다른 곳에서 다시 열 수 있게)");
        line(sender, "/wb list [페이지|날짜]", "백업 목록을 봅니다");
        line(sender, "/wb list days", "날짜별로 몇 개씩 있는지 봅니다");
        line(sender, "/wb info [ID|번호]", "백업 상세 정보를 봅니다");
        line(sender, "/wb restore", "되돌릴 시점을 목록에서 골라 클릭합니다");
        line(sender, "/wb restore [ID|번호] (worlds)", "그 시점으로 되돌립니다");
        line(sender, "/wb restore at [시각]", "시각으로 찾아 되돌립니다 (03:00, 9h)");
        line(sender, "/wb confirm", "복원을 확정합니다");
        line(sender, "/wb cancel", "복원 요청을 취소합니다");
        line(sender, "/wb delete [ID|번호] (cascade)", "백업을 삭제합니다");
        line(sender, "/wb lock, /wb unlock [ID|번호]", "자동 삭제로부터 보호/해제합니다");
        line(sender, "/wb prune", "보관 정책을 지금 적용합니다");
        line(sender, "/wb status", "현재 상태를 봅니다");
        line(sender, "/wb reload", "설정을 다시 불러옵니다");
        line(sender, "/wb update", "새 버전이 있는지 확인하고 받아 둡니다");
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

    /**
     * {@code /wb update} - 새 버전이 있는지 확인하고, 있으면 받아 둔다.
     *
     * <p>돌고 있는 jar 는 건드리지 않는다. {@code plugins/update/} 에 놓아 두면 서버가 다음에
     * 켜질 때 스스로 갈아 끼운다 - 잠긴 파일도, 옛 jar 가 남는 문제도 없다.</p>
     *
     * <p>네트워크를 쓰므로 <b>반드시 비동기</b>다. 메인 스레드에서 깃허브를 기다리면 응답이
     * 늦는 동안 서버 전체가 멈춘다.</p>
     */
    private void update(CommandSender sender) {
        BackupSettings settings = plugin.settings();
        String current = plugin.getPluginMeta().getVersion();

        Msg.send(sender, "<gray>새 버전이 있는지 확인합니다... <white>(" + settings.updateRepository() + ")</white></gray>");
        Sched.async(plugin, () -> {
            UpdateService service = new UpdateService(settings.updateRepository(), "WorldBackUp");
            UpdateService.Outcome outcome = service.download(current, plugin.updateFolder(), plugin.jarName());
            Sched.syncQuietly(plugin, () -> tell(sender, outcome, current));
        });
    }

    /** 확인 결과를 사람에게 옮긴다. 콘솔과 채팅 양쪽에서 같은 말이 되도록 한 곳에 둔다. */
    private void tell(CommandSender sender, UpdateService.Outcome outcome, String current) {
        if (outcome instanceof UpdateService.Outcome.UpToDate) {
            Msg.send(sender, "<green>이미 최신입니다.</green> <gray>(" + current + ")</gray>");
        } else if (outcome instanceof UpdateService.Outcome.Staged staged) {
            Msg.send(sender, "<green>새 버전 " + staged.release().version() + " 을 받았습니다.</green>");
            Msg.send(sender, "<gray>서버를 다시 켜면 자동으로 갈아 끼워집니다. "
                    + "지금 돌고 있는 " + current + " 은 그대로 둡니다.</gray>");
        } else if (outcome instanceof UpdateService.Outcome.Available available) {
            Msg.send(sender, "<yellow>새 버전 " + available.release().version() + " 이 있습니다.</yellow>");
            Msg.send(sender, "<gray>내려받지 못했습니다. " + Msg.sanitize(String.valueOf(available.release().pageUrl()))
                    + " 에서 직접 받으세요.</gray>");
        } else if (outcome instanceof UpdateService.Outcome.Failed failed) {
            Msg.send(sender, "<red>확인하지 못했습니다: " + Msg.sanitize(failed.reason()) + "</red>");
        }
    }

    /**
     * {@code /wb status} 의 OneBack 줄.
     *
     * <p>이것이 없으면 관리자는 <b>OneBack 이 있는지조차</b> 확인할 방법이 없다. 재해 복구용
     * 파일에서 정작 알고 싶은 것은 "있느냐, 얼마나 오래됐느냐" 인데, 그걸 보려고 서버 폴더를
     * 뒤져야 한다면 대개 보지 않는다. 그리고 필요한 날 없다는 것을 알게 된다.</p>
     */
    private void showOneBack(CommandSender sender, BackupSettings settings) {
        if (!settings.oneBackEnabled()) return;

        List<Path> archives = OneBack.list(settings.oneBackDir());
        if (archives.isEmpty()) {
            Msg.sendRaw(sender, " <gray>OneBack  :</gray> <yellow>아직 없음</yellow> <dark_gray>("
                    + "<white>/wb oneback</white> 으로 서버 한 벌을 통째로 담아 둘 수 있습니다)</dark_gray>");
            return;
        }
        String newest = OneBack.displayTime(archives.get(0)).orElse(archives.get(0).getFileName().toString());
        Msg.sendRaw(sender, " <gray>OneBack  :</gray> <white>" + archives.size() + "개</white> <dark_gray>("
                + FileUtil.humanBytes(OneBack.totalBytes(settings.oneBackDir())) + ")</dark_gray>"
                + " <gray>최근</gray> <white>" + newest + "</white>");
        Msg.sendRaw(sender, " <gray>         </gray> <dark_gray>" + settings.oneBackDir() + "</dark_gray>");
    }

    /**
     * {@code /wb oneback} - 서버 한 벌을 통째로 담는다.
     *
     * <p>평소 백업과 크기가 다르다(서버 폴더 전체). 그래서 시작하기 전에 <b>무엇이 담기고
     * 어디에 생기는지</b>를 먼저 알린다 - 몇 GB 짜리 작업을 아무 설명 없이 시작하면 관리자는
     * 서버가 멈춘 줄 안다.</p>
     */
    private void oneBack(CommandSender sender) {
        BackupSettings settings = plugin.settings();
        if (!settings.oneBackEnabled()) {
            Msg.send(sender, "<red>oneback.enabled 가 꺼져 있습니다.</red>");
            Msg.send(sender, "<gray>config.yml 의 <white>oneback</white> 항목을 켜고 "
                    + "<white>/wb reload</white> 하세요.</gray>");
            return;
        }
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>이미 백업이 진행 중입니다. <gray>("
                    + plugin.backupService().progressText() + ")</gray></red>");
            return;
        }
        // 복원 카운트다운 중이면 곧 서버가 꺼진다. 그 자리에서 시작하면 서버 폴더 크기만 한
        // 조각(.zip.tmp)만 남기고 끊긴다 - 몇 분을 쓰고 아무것도 얻지 못한다.
        if (plugin.restoreService().isCountingDown()) {
            Msg.send(sender, "<red>복원 카운트다운 중입니다. 곧 서버가 종료되므로 지금 시작하면 끊깁니다.</red>");
            Msg.send(sender, "<gray>취소하려면 <white>/wb cancel</white>, 아니면 복원이 끝난 뒤에 실행하세요.</gray>");
            return;
        }

        Msg.send(sender, "<gray>서버 폴더를 통째로 담습니다. 월드가 크면 몇 분 걸릴 수 있습니다.</gray>");
        Msg.send(sender, "<gray>저장 위치: <white>" + settings.oneBackDir() + "</white></gray>");
        plugin.backupService().startOneBackAsync(sender.getName())
                .whenComplete((result, error) -> Sched.syncQuietly(plugin, () -> {
                    if (error != null) {
                        Msg.send(sender, "<red>OneBack 실패: "
                                + Msg.sanitize(String.valueOf(error.getMessage())) + "</red>");
                        return;
                    }
                    Msg.send(sender, "<green>OneBack 완료</green> <white>"
                            + result.archive().getFileName() + "</white> <gray>("
                            + FileUtil.humanBytes(result.archiveBytes()) + ", "
                            + result.fileCount() + "개 파일, "
                            + FileUtil.humanMillis(result.elapsedMillis()) + ")</gray>");
                    Msg.send(sender, "<gray>이 폴더만 챙기면 다른 컴퓨터에서도 서버를 다시 열 수 있습니다. "
                            + "폴더의 <white>" + io.github.yj.worldbackup.backup.OneBack.GUIDE_NAME
                            + "</white> 에 방법이 적혀 있습니다.</gray>");
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
        for (BackupEntry entry : entries.stream().limit(DAY_VIEW_LIMIT).toList()) {
            Msg.sendRaw(sender, "  " + entryLine(entry));
        }
        if (entries.size() > DAY_VIEW_LIMIT) {
            Msg.sendRaw(sender, "  <dark_gray>… " + (entries.size() - DAY_VIEW_LIMIT)
                    + "개 더 있습니다. 시각을 아시면 <white>/wb restore at " + day + " 03:00</white> "
                    + "처럼 바로 지정하세요.</dark_gray>");
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

        int shown = 0;
        for (Map.Entry<LocalDate, List<BackupEntry>> day : byDay.entrySet()) {
            if (shown++ >= DAY_LIST_LIMIT) {
                Msg.sendRaw(sender, " <dark_gray>… " + (byDay.size() - DAY_LIST_LIMIT)
                        + "일 더 있습니다. <white>/wb list " + entries.get(entries.size() - 1).localDate()
                        + "</white> 처럼 날짜를 직접 넣으세요.</dark_gray>");
                break;
            }
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

    private static String dayLabel(LocalDate day) {
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

    /** {@code /wb restore} 메뉴에 띄울 시점들. 사람이 실제로 말하는 단위로 잡는다. */
    private record Preset(String label, Duration back) {
    }

    private static final List<Preset> RESTORE_PRESETS = List.of(
            new Preset("30분 전", Duration.ofMinutes(30)),
            new Preset("1시간 전", Duration.ofHours(1)),
            new Preset("3시간 전", Duration.ofHours(3)),
            new Preset("6시간 전", Duration.ofHours(6)),
            new Preset("12시간 전", Duration.ofHours(12)),
            new Preset("1일 전", Duration.ofDays(1)),
            new Preset("3일 전", Duration.ofDays(3)),
            new Preset("7일 전", Duration.ofDays(7)),
            new Preset("14일 전", Duration.ofDays(14)),
            new Preset("30일 전", Duration.ofDays(30)));

    /**
     * 인자 없는 {@code /wb restore} - 되돌릴 시점을 클릭으로 고른다.
     *
     * <p>사고를 막 발견한 사람에게 백업 ID 를 외우게 할 수는 없다. 각 줄은 그 시점에
     * <b>실제로 존재하는</b> 백업을 가리키므로, 눌렀을 때 무엇으로 돌아가는지가 화면에 그대로 보인다.
     * 같은 백업을 여러 줄에 반복해 보여 주지 않는다 - 보관이 성긴 구간에서는 여러 시점이
     * 같은 백업 하나로 모이는데, 그걸 여러 줄로 늘어놓으면 선택지가 많은 것처럼 착각하게 된다.</p>
     */
    private void restoreMenu(CommandSender sender) {
        // 목록을 띄워 놓고 눌렀을 때 거부하면 왜 안 되는지 알 수 없다. 먼저 알린다.
        if (plugin.restoreService().isCountingDown()) {
            Msg.send(sender, "<red>이미 복원 카운트다운이 진행 중입니다. "
                    + "<white>/wb cancel</white> 로 취소하세요.</red>");
            return;
        }
        if (plugin.backupService().isRunning()) {
            Msg.send(sender, "<red>백업이 진행 중이라 지금은 복원할 수 없습니다. "
                    + "<gray>(" + plugin.backupService().progressText() + ")</gray></red>");
            return;
        }

        List<BackupEntry> entries = plugin.repository().list().stream()
                .filter(BackupEntry::complete)
                .toList();
        if (entries.isEmpty()) {
            Msg.send(sender, "<red>되돌릴 수 있는 백업이 없습니다.</red>");
            Msg.send(sender, "<gray><white>/wb list</white> 로 확인하세요. 손상된 백업만 있을 수 있습니다.</gray>");
            return;
        }

        Instant now = Instant.now();
        BackupEntry oldest = entries.get(entries.size() - 1);

        Msg.sendRaw(sender, "<dark_gray>─────</dark_gray> <aqua>되돌릴 시점 고르기</aqua> <dark_gray>─────</dark_gray>");
        Msg.sendRaw(sender, "<dark_gray>보관 범위: " + FileUtil.humanDuration(
                Duration.between(oldest.createdAt(), now)) + " 전까지 (" + oldest.displayTime() + ")</dark_gray>");

        Set<String> shown = new HashSet<>();
        restoreOption(sender, "가장 최근", entries.get(0), now);
        shown.add(entries.get(0).id());

        for (Preset preset : RESTORE_PRESETS) {
            Instant target = now.minus(preset.back());
            BackupEntry match = null;
            for (BackupEntry entry : entries) { // 최신순이라 첫 항목이 가장 가까운 과거
                if (!entry.createdAt().isAfter(target)) {
                    match = entry;
                    break;
                }
            }
            if (match == null) break;             // 여기부터는 보관 범위 밖이다
            if (!shown.add(match.id())) continue; // 앞 줄과 같은 백업
            restoreOption(sender, preset.label(), match, now);
        }

        Msg.sendRaw(sender, "<dark_gray>─────────────────────────────</dark_gray>");
        Msg.sendRaw(sender, "<gray>클릭하면 <white>확인 화면</white>이 뜹니다. "
                + "실제 복원은 <white>/wb confirm</white> 을 눌러야 시작됩니다.</gray>");
        Msg.sendRaw(sender, "<dark_gray>정확한 시각을 아시면 <white>/wb restore at 03:00</white> · "
                + "날짜별로 보려면 <white>/wb list days</white></dark_gray>");
    }

    /** 메뉴 한 줄. 눌렀을 때 어디로 돌아가는지가 줄에 그대로 적혀 있어야 한다. */
    private void restoreOption(CommandSender sender, String label, BackupEntry entry, Instant now) {
        String age = FileUtil.humanDuration(Duration.between(entry.createdAt(), now));
        String tags = entry.isDifferential() ? " <dark_gray>[차등]</dark_gray>" : "";
        if (!entry.hasPlayerData()) {
            tags += entry.playerDataUnknown()
                    ? " <yellow>[플레이어?]</yellow>" : " <red>[플레이어없음]</red>";
        }
        // 콘솔은 클릭할 수 없다. ID 가 안 보이면 목록만 보고 아무것도 못 하므로 직접 적어 준다.
        if (!(sender instanceof Player)) tags += " <dark_gray>" + entry.id() + "</dark_gray>";

        Msg.sendRaw(sender, " <hover:show_text:'<gray>" + entry.id() + "</gray>"
                + "<newline><gray>" + entry.displayTime() + " 시점으로 되돌립니다</gray>'>"
                + "<click:run_command:'/wb restore " + entry.id() + "'>"
                + "<green>[" + label + "]</green></click></hover>"
                + " <white>" + entry.displayTime() + "</white>"
                + " <dark_gray>·</dark_gray> <aqua>" + FileUtil.humanBytes(entry.archiveBytes()) + "</aqua>"
                + " <dark_gray>(" + age + " 전)</dark_gray>" + tags);
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

        List<BackupEntry> all = plugin.repository().list();
        List<BackupEntry> dependents = plugin.repository().dependents(all, entry.id());

        // 지워도 되는지는 CommandGuards 가 정한다. 여기서는 그 답에 맞는 안내만 고른다.
        // (판단을 Bukkit 밖으로 빼 두어 서버 없이 검증한다 - CommandGuardsTest)
        switch (CommandGuards.delete(entry, plugin.repository().isPinned(entry), dependents, cascade)) {
            case LOCKED -> {
                Msg.send(sender, "<red>보호된 백업입니다. <white>/wb unlock " + entry.id()
                        + "</white> 후 삭제하세요.</red>");
                return;
            }
            case PINNED -> {
                Msg.send(sender, "<red>지금 만들어지는 차등 백업이 이 백업을 기준으로 삼고 있습니다.</red>");
                Msg.send(sender, "<gray>백업이 끝난 뒤 다시 시도하세요.</gray>");
                return;
            }
            case LOCKED_DEPENDENTS -> {
                List<BackupEntry> lockedDependents = CommandGuards.lockedAmong(dependents);
                Msg.send(sender, "<red>보호된 차등 백업 " + lockedDependents.size()
                        + "개가 이 백업을 기준으로 삼고 있어 삭제할 수 없습니다.</red>");
                for (BackupEntry dependent : lockedDependents) {
                    Msg.send(sender, "<gray> - <white>" + dependent.id() + "</white> <gold>[보호]</gold></gray>");
                }
                Msg.send(sender, "<gray>먼저 <white>/wb unlock [ID]</white> 로 보호를 해제하세요.</gray>");
                return;
            }
            case NEEDS_CASCADE -> {
                Msg.send(sender, "<red>이 백업을 기준으로 삼는 차등 백업이 " + dependents.size() + "개 있습니다.</red>");
                Msg.send(sender, "<gray>지우면 그 백업들도 복원할 수 없게 됩니다. 함께 지우려면 "
                        + "<white>/wb delete " + entry.id() + " cascade</white></gray>");
                return;
            }
            case OK -> {
            }
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
        // 손상된 백업은 잠글 수 없다. 복원에 쓸 수 없는 것을 남겨 뒀다고 믿게 하는 것이 더 위험하다.
        if (CommandGuards.lock(entry, locked) == CommandGuards.Lock.BROKEN) {
            Msg.send(sender, "<red>손상된 백업은 보호할 수 없습니다: <white>" + entry.id() + "</white></red>");
            Msg.send(sender, entry.isDifferential()
                    ? "<gray>기준이 되는 전체 백업이 없어 복원에 쓸 수 없습니다.</gray>"
                    : "<gray>압축이 끝나기 전에 서버가 종료된 것으로 보입니다.</gray>");
            Msg.send(sender, "<gray>되돌릴 수 있는 백업을 남기시려면 <white>/wb list</white> 에서 "
                    + "<red>[손상]</red> 표시가 없는 것을 고르세요.</gray>");
            return;
        }
        if (!plugin.repository().setLocked(entry, locked)) {
            Msg.send(sender, "<red>보호 상태를 저장하지 못했습니다. 콘솔 로그를 확인하세요.</red>");
            return;
        }
        Msg.send(sender, locked
                ? "<green>이제 이 백업은 자동으로 삭제되지 않습니다: <white>" + entry.id() + "</white></green>"
                : "<gray>보호를 해제했습니다: <white>" + entry.id() + "</white></gray>");
    }

    private void prune(CommandSender sender) {
        // /wb delete 는 이 정지에 걸리지 않는다. 그쪽은 무엇을 지우는지 관리자가 직접 지목하므로
        // "정책이 몰래 밀어내는" 일이 아니다. 여기서 막는 것은 자동 정책뿐이다.
        switch (CommandGuards.prune(plugin.backupService().isRunning(), plugin.restoreFailureHold())) {
            case BACKUP_RUNNING -> {
                Msg.send(sender, "<red>백업이 진행 중입니다. 완료 후 다시 시도하세요.</red>");
                return;
            }
            case RESTORE_FAILURE_HOLD -> {
                Msg.send(sender, "<red>복원 실패 기록이 남아 있어 보관 정리를 하지 않습니다.</red>");
                Msg.send(sender, "<gray>지금 정리하면 반쯤 복원된 월드를 되돌릴 백업이 정책에 밀려 사라질 수 있습니다.</gray>");
                Msg.send(sender, "<gray>월드를 확인한 뒤 <white>plugins/WorldBackUp/restore-failed-*.yml</white> 을 "
                        + "지우고 <white>/wb reload</white> 하세요.</gray>");
                Msg.send(sender, "<gray>특정 백업만 지우려면 <white>/wb delete [ID]</white> 는 그대로 쓸 수 있습니다.</gray>");
                return;
            }
            case OK -> {
            }
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

        String cap = "";
        if (settings.maxTotalBytes() > 0) {
            int percent = (int) Math.min(999, totalBytes * 100L / settings.maxTotalBytes());
            String color = percent >= 90 ? "<red>" : percent >= 70 ? "<yellow>" : "<green>";
            cap = " " + color + percent + "%</" + colorTag(color) + "> <dark_gray>of "
                    + FileUtil.humanBytes(settings.maxTotalBytes()) + " 상한</dark_gray>";
        }
        Msg.sendRaw(sender, " <gray>보관 중  :</gray> <white>" + entries.size() + "개</white> <dark_gray>("
                + FileUtil.humanBytes(totalBytes) + ")</dark_gray>" + cap);
        Msg.sendRaw(sender, " <gray>저장 위치:</gray> <white>" + settings.backupDir() + "</white>");
        Msg.sendRaw(sender, " <gray>디스크   :</gray> <white>"
                + FileUtil.humanBytes(FileUtil.usableSpace(settings.backupDir())) + " 남음</white>");
        showOneBack(sender, settings);
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
}
